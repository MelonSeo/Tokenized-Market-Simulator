// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Test} from "forge-std/Test.sol";
import {Vm} from "forge-std/Vm.sol";
import {stdJson} from "forge-std/StdJson.sol";
import {FuturesEngine} from "../src/FuturesEngine.sol";
import {FuturesMath as M} from "../src/FuturesMath.sol";
import {FuturesReportTypes as T} from "../src/FuturesReportTypes.sol";

contract FuturesEngineTest is Test {
    using stdJson for string;
    FuturesEngine engine;
    uint256 private signerKey = 12345; // public deterministic fixture only
    address executor = address(0xBEEF);
    bytes32 symbol = keccak256("mSEC");
    bytes32 policy = keccak256("isolated-v1-zero-fee-mmr500-grace60");
    bytes32 position = keccak256("position");
    uint256 serial;

    function setUp() public {
        vm.warp(1000);
        engine = new FuturesEngine(executor, vm.addr(signerKey), symbol, policy);
    }
    function report(uint8 action, uint256 price) internal returns (T.Report memory r) {
        FuturesEngine.Position memory p = engine.getPosition(position);
        r.operationId = bytes32(++serial); r.positionId=position; r.accountRef=keccak256("account");
        r.symbolHash=symbol; r.action=action; r.expectedVersion=p.version; r.expectedCallEpoch=p.callEpoch;
        r.side=p.side; r.leverage=p.leverage; r.quantity=p.quantity; r.entryPriceE8=p.entryPriceE8;
        r.collateralBefore=p.collateral; r.collateralAfter=p.collateral; r.priceE8=price;
        r.observedAt=block.timestamp; r.validUntil=block.timestamp+30; r.policyHash=policy; r.executor=executor;
    }
    function signature(T.Report memory r) internal view returns(bytes memory) {
        (uint8 v,bytes32 rr,bytes32 ss)=vm.sign(signerKey,engine.reportDigest(r));
        return abi.encodePacked(rr,ss,v);
    }
    function openPosition(uint8 side, uint8 leverage) internal {
        T.Report memory r=report(T.OPEN,75000e8);
        r.side=side; r.leverage=leverage; r.collateralAfter=100000e18;
        r.entryPriceE8=r.priceE8; r.quantity=M.quantity(r.collateralAfter,leverage,r.priceE8);
        bytes memory s=signature(r); vm.prank(executor); engine.open(r,s);
    }
    function callPosition(uint256 price) internal {
        T.Report memory r=report(T.START_CALL,price); bytes memory s=signature(r);
        vm.prank(executor); engine.startMarginCall(r,s);
    }

    function testGoldenFileSharedWithJava() public view {
        string memory data=vm.readFile("test/fixtures/futures-math.json");
        for(uint256 i;i<8;i++) {
            string memory path=string.concat(".vectors[",vm.toString(i),"].");
            M.Mark memory m=M.mark(uint8(data.readUint(string.concat(path,"side"))),
                data.readUint(string.concat(path,"quantity")),data.readUint(string.concat(path,"entry")),
                data.readUint(string.concat(path,"collateral")),data.readUint(string.concat(path,"price")));
            assertEq(m.unrealized,data.readInt(string.concat(path,"pnl")));
            assertEq(m.equity,data.readInt(string.concat(path,"equity")));
            assertEq(m.maintenance,data.readUint(string.concat(path,"maintenance")));
            assertEq(m.realized,data.readInt(string.concat(path,"realized")));
            assertEq(m.badDebt,data.readUint(string.concat(path,"badDebt")));
        }
    }
    function testLongShortAllLeveragesNormalClose() public {
        for(uint8 side;side<2;side++) for(uint8 index;index<3;index++) {
            uint8 l=index==0?2:index==1?5:10;
            position=keccak256(abi.encode(side,l)); openPosition(side,l);
            T.Report memory r=report(T.CLOSE,side==0?75750e8:74250e8); bytes memory s=signature(r);
            vm.prank(executor); engine.close(r,s);
            assertEq(uint8(engine.getPosition(position).status),uint8(FuturesEngine.Status.CLOSED));
            assertEq(engine.activePosition(r.accountRef),bytes32(0));
        }
    }
    function testBothSidesGraceAndLiquidation() public {
        for(uint8 side;side<2;side++) {
            position=bytes32(uint256(side)+100); openPosition(side,10);
            callPosition(side==0?70000e8:80000e8);
            uint256 deadline=engine.getPosition(position).deadline; vm.warp(deadline);
            T.Report memory r=report(T.LIQUIDATE,side==0?70000e8:80000e8); bytes memory s=signature(r);
            vm.prank(executor); engine.liquidate(r,s);
            assertEq(uint8(engine.getPosition(position).status),uint8(FuturesEngine.Status.LIQUIDATED));
        }
    }
    function testPreGraceSampleMinedAfterGraceRejectedAndFutureRejected() public {
        openPosition(0,10); callPosition(70000e8);
        uint256 deadline=engine.getPosition(position).deadline;
        vm.warp(deadline-1); T.Report memory r=report(T.LIQUIDATE,70000e8); bytes memory s=signature(r);
        vm.warp(deadline+1); vm.expectRevert(FuturesEngine.GraceNotOver.selector); vm.prank(executor); engine.liquidate(r,s);
        r=report(T.LIQUIDATE,70000e8); r.observedAt=block.timestamp+1; r.validUntil=r.observedAt+30; s=signature(r);
        vm.expectRevert(FuturesEngine.GraceNotOver.selector); vm.prank(executor); engine.liquidate(r,s);
        r=report(T.LIQUIDATE,70000e8); s=signature(r); vm.prank(executor); engine.liquidate(r,s);
    }
    function testRecoveryAndTopupClearCall() public {
        openPosition(0,10); callPosition(70000e8);
        uint256 firstDeadline=engine.getPosition(position).deadline;
        T.Report memory r=report(T.CLEAR_CALL,74000e8); bytes memory s=signature(r);
        vm.prank(executor); engine.clearMarginCall(r,s);
        assertEq(engine.getPosition(position).deadline,0);
        vm.warp(block.timestamp+5); callPosition(70000e8);
        assertEq(engine.getPosition(position).callEpoch,2);
        assertEq(engine.getPosition(position).deadline,firstDeadline+5);
        r=report(T.TOP_UP,70000e8); r.collateralAfter+=100000e18; s=signature(r);
        vm.prank(executor); engine.topUp(r,s);
        assertEq(engine.getPosition(position).deadline,0);
        assertEq(engine.getPosition(position).collateral,200000e18);
    }
    function testHealthyCannotLiquidateEvenAfterGrace() public {
        openPosition(1,10); callPosition(80000e8); vm.warp(engine.getPosition(position).deadline);
        T.Report memory r=report(T.LIQUIDATE,76000e8); bytes memory s=signature(r);
        vm.expectRevert(FuturesEngine.RiskConditionNotMet.selector); vm.prank(executor); engine.liquidate(r,s);
    }
    function testAuthoritySignatureAndReplayAndStaleVersion() public {
        openPosition(0,10);
        T.Report memory r=report(T.TOP_UP,75000e8); r.collateralAfter+=1; bytes memory s=signature(r);
        vm.expectRevert(FuturesEngine.Unauthorized.selector); engine.topUp(r,s);
        T.Report memory stale=report(T.TOP_UP,75000e8); stale.collateralAfter+=1; bytes memory staleSig=signature(stale);
        vm.prank(executor); engine.topUp(r,s);
        vm.expectRevert(FuturesEngine.AlreadyUsed.selector); vm.prank(executor); engine.topUp(r,s);
        vm.expectRevert(FuturesEngine.StaleVersion.selector); vm.prank(executor); engine.topUp(stale,staleSig);
        r=report(T.CLOSE,75000e8); s=signature(r); r.priceE8+=1;
        vm.expectRevert(FuturesEngine.Unauthorized.selector); vm.prank(executor); engine.close(r,s);
    }
    function testExpiryDomainAndReportMutation() public {
        openPosition(0,10);
        T.Report memory r=report(T.CLOSE,75000e8); bytes memory s=signature(r); vm.warp(r.validUntil+1);
        vm.expectRevert(FuturesEngine.InvalidReport.selector); vm.prank(executor); engine.close(r,s);
        r=report(T.CLOSE,75000e8); s=signature(r); r.policyHash=bytes32(uint256(3));
        vm.expectRevert(FuturesEngine.InvalidReport.selector); vm.prank(executor); engine.close(r,s);
        r=report(T.CLOSE,75000e8); s=signature(r); FuturesEngine other=new FuturesEngine(executor,vm.addr(signerKey),symbol,policy);
        vm.expectRevert(FuturesEngine.Unauthorized.selector); vm.prank(executor); other.close(r,s);
    }
    function testOneActivePositionAndNoReopen() public {
        openPosition(0,10); bytes32 original=position; position=keccak256("another");
        T.Report memory r=report(T.OPEN,75000e8); r.leverage=10; r.collateralAfter=100000e18;
        r.entryPriceE8=r.priceE8; r.quantity=M.quantity(r.collateralAfter,10,r.priceE8); bytes memory s=signature(r);
        vm.expectRevert(FuturesEngine.InvalidState.selector); vm.prank(executor); engine.open(r,s);
        position=original; r=report(T.CLOSE,75000e8); s=signature(r); vm.prank(executor); engine.close(r,s);
        r=report(T.OPEN,75000e8); s=signature(r);
        vm.expectRevert(FuturesEngine.InvalidState.selector); vm.prank(executor); engine.open(r,s);
    }
    function testCannotWithdrawChangeQuantityOrUseOldObservation() public {
        openPosition(0,10);
        T.Report memory r=report(T.TOP_UP,75000e8); r.collateralAfter-=1; bytes memory s=signature(r);
        vm.expectRevert(FuturesEngine.InvalidReport.selector); vm.prank(executor); engine.topUp(r,s);
        r=report(T.CLOSE,75000e8); r.quantity+=1; s=signature(r);
        vm.expectRevert(FuturesEngine.InvalidReport.selector); vm.prank(executor); engine.close(r,s);
        r=report(T.CLOSE,75000e8); r.observedAt-=1; r.validUntil=r.observedAt+30; s=signature(r);
        vm.expectRevert(FuturesEngine.InvalidReport.selector); vm.prank(executor); engine.close(r,s);
    }
    function testLiquidationBeforeDeadlineAndWrongEpochRejected() public {
        openPosition(0,10); callPosition(70000e8);
        uint256 deadline=engine.getPosition(position).deadline;
        vm.warp(deadline-1); T.Report memory r=report(T.LIQUIDATE,70000e8); bytes memory s=signature(r);
        vm.expectRevert(FuturesEngine.GraceNotOver.selector); vm.prank(executor); engine.liquidate(r,s);
        vm.warp(deadline); r=report(T.LIQUIDATE,70000e8); r.expectedCallEpoch+=1; s=signature(r);
        vm.expectRevert(FuturesEngine.StaleVersion.selector); vm.prank(executor); engine.liquidate(r,s);
    }
    function testWrongChainAndKeyAndSymbolRejected() public {
        openPosition(0,10); T.Report memory r=report(T.CLOSE,75000e8);
        uint256 original=block.chainid; vm.chainId(original+1); bytes memory s=signature(r); vm.chainId(original);
        vm.expectRevert(FuturesEngine.Unauthorized.selector); vm.prank(executor); engine.close(r,s);
        (uint8 v,bytes32 rr,bytes32 ss)=vm.sign(signerKey+1,engine.reportDigest(r));
        s=abi.encodePacked(rr,ss,v);
        vm.expectRevert(FuturesEngine.Unauthorized.selector); vm.prank(executor); engine.close(r,s);
        r.symbolHash=keccak256("another"); s=signature(r);
        vm.expectRevert(FuturesEngine.InvalidReport.selector); vm.prank(executor); engine.close(r,s);
    }
    function testGapLossAndCollateralNotTransferred() public {
        openPosition(0,10); callPosition(60000e8); vm.warp(engine.getPosition(position).deadline);
        T.Report memory r=report(T.LIQUIDATE,60000e8); bytes memory s=signature(r);
        vm.recordLogs(); vm.prank(executor); engine.liquidate(r,s);
        Vm.Log[] memory logs=vm.getRecordedLogs();
        (,,,,int256 pnl,int256 realized,uint256 debt,,)=abi.decode(logs[0].data,
            (uint8,uint256,uint256,uint256,int256,int256,uint256,uint256,uint256));
        assertLt(pnl,-int256(100000e18)); assertEq(realized,-int256(100000e18));
        assertGt(debt,0); assertEq(address(engine).balance,0);
    }
    function testWrongValidityDoesNotConsumeOperation() public {
        openPosition(0,10); T.Report memory r=report(T.CLOSE,75000e8); r.validUntil+=1; bytes memory s=signature(r);
        vm.expectRevert(FuturesEngine.InvalidReport.selector); vm.prank(executor); engine.close(r,s);
        assertFalse(engine.usedOperations(r.operationId));
        r.validUntil-=1; s=signature(r); vm.prank(executor); engine.close(r,s);
        assertTrue(engine.usedOperations(r.operationId));
    }
    function mathQuantity(uint256 c,uint8 l,uint256 p) external pure returns(uint256) { return M.quantity(c,l,p); }
    function testMaximumBoundaryAndInvalidZeroUpperBound() public {
        M.Mark memory m=M.mark(1,1e30-1,1,1e30-1,1e20-1);
        assertGe(m.realized,-int256(1e30-1)); assertGt(m.badDebt,0);
        vm.expectRevert(M.InvalidValue.selector); this.mathQuantity(0,10,75000e8);
        vm.expectRevert(M.InvalidValue.selector); this.mathQuantity(1e30,10,75000e8);
        vm.expectRevert(M.InvalidValue.selector); this.mathQuantity(1,10,1e20);
        vm.expectRevert(M.InvalidValue.selector); this.mathQuantity(1,3,75000e8);
    }
    function testFuzzIsolatedLossBound(uint96 cSeed,uint96 qSeed,uint64 pSeed,bool shortSide) public pure {
        uint256 c=uint256(cSeed)+1; uint256 q=uint256(qSeed)+1; uint256 p=uint256(pSeed)+1;
        M.Mark memory m=M.mark(shortSide?1:0,q,75000e8,c,p);
        assertGe(m.realized,-int256(c));
        assertEq(m.badDebt,m.equity<0?uint256(-m.equity):0);
        assertEq(m.realized,m.unrealized < -int256(c)?-int256(c):m.unrealized);
    }
}
