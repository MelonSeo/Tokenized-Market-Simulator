// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Test} from "forge-std/Test.sol";
import {Vm} from "forge-std/Vm.sol";
import {UserAuthorizedFuturesEngineV2 as C} from "../src/UserAuthorizedFuturesEngineV2.sol";
import {FuturesEngine as E} from "../src/FuturesEngine.sol";
import {FuturesReportTypes as T} from "../src/FuturesReportTypes.sol";
import {FuturesUserIntentV2Types as U} from "../src/FuturesUserIntentV2Types.sol";
import {FuturesMath as M} from "../src/FuturesMath.sol";

contract UserAuthorizedFuturesEngineV2Test is Test {
    // Public deterministic EOA fixtures only, never production keys.
    uint256 constant USER_KEY=111;
    uint256 constant PRICE_KEY=333;
    address constant RELAYER=address(0xBEEF);
    bytes32 constant SYMBOL=keccak256("mSEC");
    bytes32 constant POLICY=keccak256("isolated-v1-zero-fee-mmr500-grace60");
    C c; E rules; address user; uint256 serial;
    bytes32 pid=keccak256("v2 position");

    function setUp() public {vm.warp(1000);user=vm.addr(USER_KEY);deploy();}
    function deploy() internal {c=new C(RELAYER,vm.addr(PRICE_KEY),SYMBOL,POLICY);rules=c.rules();}
    function report(uint8 action,uint256 price) internal returns(T.Report memory r){
        E.Position memory p=rules.getPosition(pid);
        r=T.Report(bytes32(++serial),pid,c.accountRef(user),SYMBOL,action,p.version,p.callEpoch,p.side,p.leverage,
            p.quantity,p.entryPriceE8,p.collateral,p.collateral,price,block.timestamp,block.timestamp+30,POLICY,address(c));
    }
    function opening(uint8 side,uint8 leverage,uint256 price) internal returns(T.Report memory r){
        r=report(T.OPEN,price);r.side=side;r.leverage=leverage;r.collateralAfter=100000e18;
        r.entryPriceE8=price;r.quantity=M.quantity(r.collateralAfter,leverage,price);
    }
    function intent(T.Report memory r) internal view returns(U.Intent memory i){
        i=U.Intent(user,RELAYER,r.operationId,r.positionId,r.accountRef,r.symbolHash,r.action,
            r.expectedVersion,r.expectedCallEpoch,r.side,r.leverage,r.action==T.OPEN?0:r.entryPriceE8,
            r.collateralBefore,r.collateralAfter,74000e8,76000e8,
            r.action==T.OPEN?M.quantity(r.collateralAfter,r.leverage,76000e8):r.quantity,
            r.action==T.OPEN?M.quantity(r.collateralAfter,r.leverage,74000e8):r.quantity,
            c.nonces(user),block.timestamp,block.timestamp+120,r.policyHash);
    }
    function sign(uint256 key,bytes32 digest) internal pure returns(bytes memory){
        (uint8 v,bytes32 r,bytes32 s)=vm.sign(key,digest);return abi.encodePacked(r,s,v);
    }
    function priceSig(T.Report memory r) internal view returns(bytes memory){return sign(PRICE_KEY,rules.reportDigest(r));}
    function dispatch(T.Report memory r,bytes memory ps,U.Intent memory i,bytes memory us) internal {
        vm.prank(RELAYER);
        if(r.action==T.OPEN)c.open(r,ps,i,us);
        else if(r.action==T.TOP_UP)c.topUp(r,ps,i,us);
        else c.close(r,ps,i,us);
    }
    function execute(T.Report memory r) internal {U.Intent memory i=intent(r);dispatch(r,priceSig(r),i,sign(USER_KEY,c.intentDigest(i)));}
    function automatic(T.Report memory r) internal {
        bytes memory ps=priceSig(r);vm.prank(RELAYER);
        if(r.action==T.START_CALL)c.startMarginCall(r,ps);
        else if(r.action==T.CLEAR_CALL)c.clearMarginCall(r,ps);
        else c.liquidate(r,ps);
    }

    function testCompositionAndNewUserDomain() public {
        assertEq(rules.executor(),address(c));assertEq(c.executor(),RELAYER);
        T.Report memory r=opening(0,10,75000e8);U.Intent memory i=intent(r);
        bytes32 domain=keccak256(abi.encode(keccak256("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"),keccak256("TokenizedMarketFuturesUserIntent"),keccak256("2"),block.chainid,address(c)));
        bytes32 typeHash=keccak256("FuturesUserIntentV2(address owner,address relayer,bytes32 operationId,bytes32 positionId,bytes32 accountRef,bytes32 symbolHash,uint8 action,uint256 expectedVersion,uint256 expectedCallEpoch,uint8 side,uint8 leverage,uint256 entryPriceE8,uint256 collateralBefore,uint256 collateralAfter,uint256 priceMinE8,uint256 priceMaxE8,uint256 quantityMin,uint256 quantityMax,uint256 nonce,uint256 issuedAt,uint256 deadline,bytes32 policyHash)");
        assertEq(c.intentDigest(i),keccak256(abi.encodePacked(hex"1901",domain,keccak256(abi.encode(typeHash,i)))));
    }
    function testSignatureWaitOverFiveSecondsThenFreshChangedPriceReportSucceeds() public {
        T.Report memory r=opening(0,10,75000e8);U.Intent memory i=intent(r);bytes memory us=sign(USER_KEY,c.intentDigest(i));
        vm.warp(1010);r.priceE8=75500e8;r.entryPriceE8=r.priceE8;r.quantity=M.quantity(r.collateralAfter,r.leverage,r.priceE8);r.observedAt=block.timestamp;r.validUntil=block.timestamp+30;
        dispatch(r,priceSig(r),i,us);
        E.Position memory p=rules.getPosition(pid);assertEq(p.entryPriceE8,75500e8);assertEq(p.quantity,r.quantity);assertEq(c.positionOwner(pid),user);assertEq(c.nonces(user),1);
    }
    function testNormalTopUpAndCloseUseExactPositionAndNewReports() public {
        execute(opening(1,5,75000e8));T.Report memory r=report(T.TOP_UP,75000e8);r.collateralAfter+=10000e18;
        U.Intent memory i=intent(r);bytes memory us=sign(USER_KEY,c.intentDigest(i));vm.warp(1010);r.priceE8=74900e8;r.observedAt=1010;r.validUntil=1040;dispatch(r,priceSig(r),i,us);
        r=report(T.CLOSE,75000e8);i=intent(r);us=sign(USER_KEY,c.intentDigest(i));vm.warp(1020);r.priceE8=74800e8;r.observedAt=1020;r.validUntil=1050;dispatch(r,priceSig(r),i,us);
        assertEq(uint8(rules.getPosition(pid).status),uint8(E.Status.CLOSED));assertEq(c.nonces(user),3);
    }
    function testPriceAndQuantityBoundsInclusive() public {
        for(uint256 n;n<2;++n){deploy();uint256 price=n==0?74000e8:76000e8;T.Report memory r=opening(0,10,price);execute(r);assertEq(rules.getPosition(pid).quantity,r.quantity);}
    }
    function testPriceOutsideEitherBoundRollsBackOwnerAndNonce() public {
        for(uint256 n;n<2;++n){T.Report memory r=opening(0,10,n==0?73999e8:76001e8);U.Intent memory i=intent(r);bytes memory ps=priceSig(r);bytes memory us=sign(USER_KEY,c.intentDigest(i));
            vm.expectRevert(C.ExecutionOutsideBounds.selector);dispatch(r,ps,i,us);assertEq(c.nonces(user),0);assertEq(c.positionOwner(pid),address(0));}
    }
    function testQuantityOutsideEitherBoundRejected() public {
        for(uint256 n;n<2;++n){T.Report memory r=opening(0,10,75000e8);U.Intent memory i=intent(r);if(n==0)i.quantityMin=r.quantity+1;else i.quantityMax=r.quantity-1;
            bytes memory us=sign(USER_KEY,c.intentDigest(i));bytes memory ps=priceSig(r);vm.expectRevert(C.ExecutionOutsideBounds.selector);dispatch(r,ps,i,us);}
    }
    function testZeroInvertedAndUnboundedRangesRejected() public {
        for(uint256 n;n<6;++n){T.Report memory r=opening(0,10,75000e8);U.Intent memory i=intent(r);
            if(n==0)i.priceMinE8=0;else if(n==1)i.priceMinE8=i.priceMaxE8+1;else if(n==2)i.priceMaxE8=type(uint256).max;
            else if(n==3)i.quantityMin=0;else if(n==4)i.quantityMin=i.quantityMax+1;else i.quantityMax=type(uint256).max;
            bytes memory us=sign(USER_KEY,c.intentDigest(i));bytes memory ps=priceSig(r);vm.expectRevert(C.InvalidBounds.selector);dispatch(r,ps,i,us);}
    }
    function testQuantityFormulaCannotBeReplacedByAnyInRangeValue() public {
        T.Report memory r=opening(0,10,75000e8);U.Intent memory i=intent(r);++r.quantity;
        bytes memory us=sign(USER_KEY,c.intentDigest(i));bytes memory ps=priceSig(r);vm.expectRevert(C.IntentReportMismatch.selector);dispatch(r,ps,i,us);
    }
    function testEveryStableReportFieldBoundEvenIfServerResigns() public {
        for(uint256 n;n<15;++n){T.Report memory r=opening(0,10,75000e8);U.Intent memory i=intent(r);bytes memory us=sign(USER_KEY,c.intentDigest(i));
            if(n==0)r.operationId=bytes32(uint256(r.operationId)+1);else if(n==1)r.positionId=bytes32(uint256(r.positionId)+1);
            else if(n==2)r.accountRef=bytes32(uint256(r.accountRef)+1);else if(n==3)r.symbolHash=bytes32(uint256(r.symbolHash)+1);
            else if(n==4)r.action=T.CLOSE;else if(n==5)++r.expectedVersion;else if(n==6)++r.expectedCallEpoch;
            else if(n==7)r.side=1;else if(n==8)r.leverage=5;else if(n==9)++r.quantity;else if(n==10)++r.entryPriceE8;
            else if(n==11)++r.collateralBefore;else if(n==12)++r.collateralAfter;else if(n==13)r.policyHash=bytes32(uint256(r.policyHash)+1);else r.executor=RELAYER;
            bytes memory ps=priceSig(r);vm.expectRevert();dispatch(r,ps,i,us);assertEq(c.nonces(user),0);}
    }
    function testAllTwentyTwoIntentFieldsCoveredBySignature() public {
        for(uint256 n;n<22;++n){T.Report memory r=opening(0,10,75000e8);U.Intent memory i=intent(r);bytes memory us=sign(USER_KEY,c.intentDigest(i));bytes memory raw=abi.encode(i);
            assembly {let word:=add(add(raw,32),mul(n,32)) mstore(word,add(mload(word),1))}
            i=abi.decode(raw,(U.Intent));bytes memory ps=priceSig(r);vm.expectRevert();dispatch(r,ps,i,us);assertEq(c.nonces(user),0);}
    }
    function testIndependentUserAndReportExpiryAndInclusiveBoundaries() public {
        T.Report memory r=opening(0,10,75000e8);U.Intent memory i=intent(r);bytes memory us=sign(USER_KEY,c.intentDigest(i));bytes memory ps=priceSig(r);
        vm.warp(1031);vm.expectRevert(E.InvalidReport.selector);dispatch(r,ps,i,us);assertEq(c.nonces(user),0);assertEq(c.positionOwner(pid),address(0));
        r.observedAt=1031;r.validUntil=1061;ps=priceSig(r);vm.warp(1121);vm.expectRevert(C.InvalidUserWindow.selector);dispatch(r,ps,i,us);
        // New report at the exact user deadline; report deadline remains independent.
        vm.warp(1120);r.observedAt=1120;r.validUntil=1150;dispatch(r,priceSig(r),i,us);assertEq(c.nonces(user),1);
    }
    function testReportThirtySecondBoundaryAndFutureObservationRulesPreserved() public {
        T.Report memory r=opening(0,10,75000e8);U.Intent memory i=intent(r);bytes memory us=sign(USER_KEY,c.intentDigest(i));bytes memory ps=priceSig(r);
        vm.warp(1030);dispatch(r,ps,i,us);assertEq(c.nonces(user),1);
        r=report(T.TOP_UP,75000e8);r.collateralAfter+=1;i=intent(r);us=sign(USER_KEY,c.intentDigest(i));r.observedAt=block.timestamp+3;r.validUntil=r.observedAt+30;ps=priceSig(r);
        vm.expectRevert(E.InvalidReport.selector);dispatch(r,ps,i,us);
    }
    function testUserWindowNeverExceedsOneHundredTwentySeconds() public {
        for(uint256 n;n<3;++n){T.Report memory r=opening(0,10,75000e8);U.Intent memory i=intent(r);
            if(n==0)i.deadline=i.issuedAt+121;else if(n==1)i.deadline=i.issuedAt;else ++i.issuedAt;
            bytes memory us=sign(USER_KEY,c.intentDigest(i));bytes memory ps=priceSig(r);vm.expectRevert(C.InvalidUserWindow.selector);dispatch(r,ps,i,us);}
    }
    function testMissingAndOtherOwnerSignatureRejectedForAllUserActions() public {
        for(uint256 action;action<3;++action){if(action==1)execute(opening(0,10,75000e8));
            T.Report memory r=action==0?opening(0,10,75000e8):report(action==1?T.TOP_UP:T.CLOSE,75000e8);if(action==1)r.collateralAfter+=1;
            U.Intent memory i=intent(r);bytes memory ps=priceSig(r);vm.expectRevert();dispatch(r,ps,i,"");
            bytes memory us=sign(222,c.intentDigest(i));vm.expectRevert(C.InvalidUserSignature.selector);dispatch(r,ps,i,us);}
    }
    function testNonceReplayAndWrongOwnerCannotClose() public {
        T.Report memory r=opening(0,10,75000e8);U.Intent memory i=intent(r);bytes memory us=sign(USER_KEY,c.intentDigest(i));bytes memory ps=priceSig(r);dispatch(r,ps,i,us);
        vm.expectRevert(C.InvalidNonce.selector);dispatch(r,ps,i,us);
        r=report(T.CLOSE,75000e8);i=intent(r);i.owner=vm.addr(222);i.accountRef=c.accountRef(i.owner);r.accountRef=i.accountRef;ps=priceSig(r);us=sign(222,c.intentDigest(i));vm.expectRevert(C.InvalidOwner.selector);dispatch(r,ps,i,us);
    }
    function testDifferentChainContractAndV1DomainRejected() public {
        T.Report memory r=opening(0,10,75000e8);U.Intent memory i=intent(r);bytes memory us=sign(USER_KEY,c.intentDigest(i));bytes memory ps=priceSig(r);
        uint256 chain=block.chainid;vm.chainId(chain+1);vm.expectRevert(C.InvalidUserSignature.selector);dispatch(r,ps,i,us);vm.chainId(chain);
        C other=new C(RELAYER,vm.addr(PRICE_KEY),SYMBOL,POLICY);bytes memory wrong=sign(USER_KEY,other.intentDigest(i));vm.expectRevert(C.InvalidUserSignature.selector);dispatch(r,ps,i,wrong);
        bytes32 domain=keccak256(abi.encode(keccak256("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"),keccak256("TokenizedMarketFuturesUserIntent"),keccak256("1"),chain,address(c)));
        wrong=sign(USER_KEY,keccak256(abi.encodePacked(hex"1901",domain,keccak256(abi.encode(U.TYPE_HASH,i)))));vm.expectRevert(C.InvalidUserSignature.selector);dispatch(r,ps,i,wrong);
    }
    function testWrongPriceSignerDomainAndExecutorRejected() public {
        T.Report memory r=opening(0,10,75000e8);U.Intent memory i=intent(r);bytes memory us=sign(USER_KEY,c.intentDigest(i));bytes memory wrong=sign(222,rules.reportDigest(r));
        vm.expectRevert(E.Unauthorized.selector);dispatch(r,wrong,i,us);
        // A sibling engine has a different price-domain verifyingContract.
        E other=new E(address(c),vm.addr(PRICE_KEY),SYMBOL,POLICY);wrong=sign(PRICE_KEY,other.reportDigest(r));vm.expectRevert(E.Unauthorized.selector);dispatch(r,wrong,i,us);
        r.executor=RELAYER;wrong=priceSig(r);vm.expectRevert(C.IntentReportMismatch.selector);dispatch(r,wrong,i,us);
    }
    function testNoLegacyUnsignedOrDirectRulesBypass() public {
        T.Report memory r=opening(0,10,75000e8);bytes memory ps=priceSig(r);
        for(uint256 n;n<3;++n){string memory name=n==0?"open":n==1?"topUp":"close";
            bytes4 selector=bytes4(keccak256(bytes(string.concat(name,"((bytes32,bytes32,bytes32,bytes32,uint8,uint256,uint256,uint8,uint8,uint256,uint256,uint256,uint256,uint256,uint256,uint256,bytes32,address),bytes)"))));
            vm.prank(RELAYER);(bool ok,)=address(c).call(abi.encodeWithSelector(selector,r,ps));assertFalse(ok);}
        vm.expectRevert(E.Unauthorized.selector);vm.prank(RELAYER);rules.open(r,ps);
        U.Intent memory i=intent(r);bytes memory us=sign(USER_KEY,c.intentDigest(i));vm.expectRevert(C.UnauthorizedRelayer.selector);c.open(r,ps,i,us);
    }
    function testCancellationInvalidatesOnlyCallerNonceAndOldSignature() public {
        T.Report memory r=opening(0,10,75000e8);U.Intent memory i=intent(r);bytes memory us=sign(USER_KEY,c.intentDigest(i));bytes memory ps=priceSig(r);
        vm.prank(RELAYER);c.cancelNonce(0);assertEq(c.nonces(user),0);assertEq(c.nonces(RELAYER),1);
        vm.expectEmit(true,false,false,true,address(c));emit C.UserNonceCancelledV2(user,0);vm.prank(user);c.cancelNonce(0);
        vm.expectRevert(C.InvalidNonce.selector);dispatch(r,ps,i,us);vm.expectRevert(C.InvalidNonce.selector);vm.prank(user);c.cancelNonce(0);
        i.nonce=1;dispatch(r,ps,i,sign(USER_KEY,c.intentDigest(i)));assertEq(c.nonces(user),2);
    }
    function testExecutionWinningCancellationRaceCannotBeUndone() public {
        execute(opening(0,10,75000e8));vm.expectRevert(C.InvalidNonce.selector);vm.prank(user);c.cancelNonce(0);
        assertEq(uint8(rules.getPosition(pid).status),uint8(E.Status.ACTIVE));
    }
    function testNonEoaCannotApproveOrCancel() public {
        T.Report memory r=opening(0,10,75000e8);U.Intent memory i=intent(r);vm.etch(user,hex"6000");bytes memory us=sign(USER_KEY,c.intentDigest(i));bytes memory ps=priceSig(r);
        vm.expectRevert(C.InvalidOwner.selector);dispatch(r,ps,i,us);vm.expectRevert(C.InvalidOwner.selector);vm.prank(user);c.cancelNonce(0);
    }
    function testTwoRulesEventsAndV2IntentReportDigestLink() public {
        T.Report memory r=opening(0,10,75000e8);U.Intent memory i=intent(r);bytes memory us=sign(USER_KEY,c.intentDigest(i));bytes memory ps=priceSig(r);
        vm.recordLogs();dispatch(r,ps,i,us);Vm.Log[] memory logs=vm.getRecordedLogs();assertEq(logs.length,3);assertEq(logs[0].emitter,address(rules));assertEq(logs[1].emitter,address(rules));assertEq(logs[2].emitter,address(c));
        assertEq(logs[2].topics[0],keccak256("UserIntentExecutedV2(bytes32,address,bytes32,bytes32,bytes32,uint8,uint256)"));assertEq(logs[2].topics[1],c.intentDigest(i));assertEq(logs[2].topics[2],bytes32(uint256(uint160(user))));assertEq(logs[2].topics[3],pid);
        (bytes32 op,bytes32 digest,uint8 action,uint256 nonce)=abi.decode(logs[2].data,(bytes32,bytes32,uint8,uint256));assertEq(op,r.operationId);assertEq(digest,rules.reportDigest(r));assertEq(action,T.OPEN);assertEq(nonce,0);
    }
    function testBothSidesAllLeveragesSixtySecondGraceAndLiquidation() public {
        for(uint8 side;side<2;++side)for(uint8 n;n<3;++n){deploy();execute(opening(side,n==0?2:n==1?5:10,75000e8));
            uint256 price=side==0?30000e8:120000e8;automatic(report(T.START_CALL,price));uint256 deadline=rules.getPosition(pid).deadline;assertEq(deadline,block.timestamp+60);
            uint256 nonce=c.nonces(user);vm.prank(user);c.cancelNonce(nonce); // Cannot revoke automatic risk policy consent.
            vm.warp(deadline-1);T.Report memory r=report(T.LIQUIDATE,price);bytes memory ps=priceSig(r);vm.expectRevert(E.GraceNotOver.selector);vm.prank(RELAYER);c.liquidate(r,ps);
            vm.warp(deadline);automatic(report(T.LIQUIDATE,price));assertEq(uint8(rules.getPosition(pid).status),uint8(E.Status.LIQUIDATED));}
    }
    function testRecoveryTopUpAndAutomaticCloseDisguiseBlocked() public {
        execute(opening(0,10,75000e8));automatic(report(T.START_CALL,70000e8));automatic(report(T.CLEAR_CALL,75000e8));assertEq(uint8(rules.getPosition(pid).status),uint8(E.Status.ACTIVE));
        automatic(report(T.START_CALL,70000e8));T.Report memory r=report(T.TOP_UP,70000e8);r.collateralAfter+=100000e18;U.Intent memory i=intent(r);i.priceMinE8=70000e8;dispatch(r,priceSig(r),i,sign(USER_KEY,c.intentDigest(i)));assertEq(rules.getPosition(pid).deadline,0);
        r=report(T.CLOSE,75000e8);bytes memory ps=priceSig(r);vm.expectRevert(E.InvalidReport.selector);vm.prank(RELAYER);c.liquidate(r,ps);
        r=report(T.LIQUIDATE,75000e8);ps=priceSig(r);vm.expectRevert(E.InvalidState.selector);vm.prank(RELAYER);c.liquidate(r,ps);
    }
    function testNonOpenQuantityRangeMustBeExact() public {
        execute(opening(0,10,75000e8));T.Report memory r=report(T.CLOSE,75000e8);U.Intent memory i=intent(r);--i.quantityMin;bytes memory ps=priceSig(r);bytes memory us=sign(USER_KEY,c.intentDigest(i));vm.expectRevert(C.IntentReportMismatch.selector);dispatch(r,ps,i,us);
    }
    function testFuzzOpenMathUnchangedInsideExplicitBounds(uint64 seed,uint8 lev,bool shortSide) public {
        uint256 price=bound(uint256(seed),74000e8,76000e8);uint8 leverage=lev%3==0?2:lev%3==1?5:10;
        T.Report memory r=opening(shortSide?1:0,leverage,price);execute(r);E.Position memory p=rules.getPosition(pid);
        assertEq(p.quantity,M.quantity(100000e18,leverage,price));assertEq(p.entryPriceE8,price);assertEq(p.collateral,100000e18);
    }
    function testDuplicateServerOperationRevertsUserNonceAndNewReport() public {
        T.Report memory opened=opening(0,10,75000e8);execute(opened);
        T.Report memory r=report(T.TOP_UP,75000e8);r.collateralAfter+=1;r.operationId=opened.operationId;
        U.Intent memory i=intent(r);bytes memory us=sign(USER_KEY,c.intentDigest(i));bytes memory ps=priceSig(r);
        vm.expectRevert(E.AlreadyUsed.selector);dispatch(r,ps,i,us);assertEq(c.nonces(user),1);assertEq(rules.getPosition(pid).collateral,100000e18);
    }
    function testAllInnerAndAutomaticEntrypointsRequireTheirActualExecutor() public {
        T.Report memory r=opening(0,10,75000e8);bytes memory ps=priceSig(r);
        for(uint8 n;n<6;++n){vm.expectRevert(E.Unauthorized.selector);vm.prank(RELAYER);
            if(n==0)rules.open(r,ps);else if(n==1)rules.topUp(r,ps);else if(n==2)rules.startMarginCall(r,ps);else if(n==3)rules.clearMarginCall(r,ps);else if(n==4)rules.close(r,ps);else rules.liquidate(r,ps);}
        execute(r);r=report(T.START_CALL,70000e8);ps=priceSig(r);
        for(uint8 n;n<3;++n){vm.expectRevert(C.UnauthorizedRelayer.selector);
            if(n==0)c.startMarginCall(r,ps);else if(n==1)c.clearMarginCall(r,ps);else c.liquidate(r,ps);}
    }
}
