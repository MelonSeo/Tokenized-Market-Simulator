// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Test} from "forge-std/Test.sol";
import {CMockCollateral} from "../src/CMockCollateral.sol";
import {CMarginVault} from "../src/CMarginVault.sol";
import {CCollateralizedFuturesController as C} from "../src/CCollateralizedFuturesController.sol";
import {CFuturesIntentTypes as U} from "../src/CFuturesIntentTypes.sol";
import {FuturesEngine as E} from "../src/FuturesEngine.sol";
import {FuturesMath as M} from "../src/FuturesMath.sol";
import {FuturesReportTypes as T} from "../src/FuturesReportTypes.sol";

contract CFuturesCustodyTest is Test {
    uint256 constant USER_KEY = 101;
    uint256 constant OTHER_KEY = 102;
    uint256 constant ORACLE_KEY = 201;
    address owner;
    address other;
    address relayer = address(301);
    address admin = address(401);
    CMockCollateral token;
    C controller;
    CMarginVault vault;
    E rules;
    bytes32 constant POS = keccak256("C-position");
    uint256 serial;

    function setUp() public {
        vm.warp(1000);
        owner = vm.addr(USER_KEY); other = vm.addr(OTHER_KEY);
        token = new CMockCollateral(address(this));
        controller = new C(address(token), relayer, vm.addr(ORACLE_KEY), admin,
            keccak256("mSEC"), keccak256("C-claims-policy-v1"));
        vault = controller.vault(); rules = controller.rules();
        token.transfer(owner, 10000e18); token.transfer(other, 10000e18);
        vm.startPrank(owner); token.approve(address(vault), 10000e18); vault.deposit(10000e18); vm.stopPrank();
        vm.startPrank(other); token.approve(address(vault), 10000e18); vault.deposit(10000e18); vm.stopPrank();
        token.approve(address(vault), 1000e18); vault.fundHouse(1000e18);
    }
    function _sign(uint256 key, bytes32 digest) private pure returns (bytes memory) {
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(key, digest);
        return abi.encodePacked(r, s, v);
    }
    function _report(uint8 action, uint256 price) private returns (T.Report memory r) {
        E.Position memory p;
        if (action != T.OPEN) p = rules.getPosition(POS);
        r.operationId = bytes32(++serial); r.positionId = POS;
        r.accountRef = controller.accountRef(owner); r.symbolHash = rules.supportedSymbolHash();
        r.action = action; r.expectedVersion = p.version; r.expectedCallEpoch = p.callEpoch;
        r.side = p.side; r.leverage = action == T.OPEN ? 10 : p.leverage;
        r.collateralBefore = p.collateral;
        r.collateralAfter = action == T.OPEN ? 100e18 : p.collateral;
        r.quantity = action == T.OPEN ? M.quantity(r.collateralAfter, r.leverage, price) : p.quantity;
        r.entryPriceE8 = action == T.OPEN ? price : p.entryPriceE8;
        r.priceE8 = price; r.observedAt = block.timestamp; r.validUntil = block.timestamp + 30;
        r.policyHash = rules.policyHash(); r.executor = address(controller);
    }
    function _intent(T.Report memory r) private view returns (U.Intent memory i) {
        i.owner = owner; i.relayer = relayer; i.operationId = r.operationId; i.positionId = r.positionId;
        i.accountRef = r.accountRef; i.symbolHash = r.symbolHash; i.action = r.action;
        i.expectedVersion = r.expectedVersion; i.expectedCallEpoch = r.expectedCallEpoch;
        i.side = r.side; i.leverage = r.leverage;
        i.entryPriceE8 = r.action == T.OPEN ? 0 : r.entryPriceE8;
        i.collateralBefore = r.collateralBefore; i.collateralAfter = r.collateralAfter;
        i.priceMinE8 = r.priceE8; i.priceMaxE8 = r.priceE8;
        i.quantityMin = r.quantity; i.quantityMax = r.quantity;
        i.nonce = controller.nonces(owner); i.issuedAt = block.timestamp;
        i.deadline = block.timestamp + 120; i.policyHash = r.policyHash;
    }
    function _direct(T.Report memory r) private {
        U.Intent memory i = _intent(r); bytes memory s = _sign(ORACLE_KEY, rules.reportDigest(r));
        vm.prank(owner); controller.executeDirect(r, s, i);
    }
    function _relay(T.Report memory r) private {
        U.Intent memory i = _intent(r);
        bytes memory s = _sign(ORACLE_KEY, rules.reportDigest(r));
        bytes memory u = _sign(USER_KEY, controller.intentDigest(i));
        vm.prank(relayer); controller.executeRelayed(r, s, i, u);
    }
    function _risk(T.Report memory r) private {
        bytes memory s = _sign(ORACLE_KEY, rules.reportDigest(r));
        vm.prank(relayer);
        if (r.action == T.START_CALL) controller.startMarginCall(r, s);
        else if (r.action == T.CLEAR_CALL) controller.clearMarginCall(r, s);
        else controller.liquidate(r, s);
    }
    function _assertCash() private view {
        assertEq(vault.recognizedAssets(), vault.totalFree() + vault.totalLocked() + vault.houseBalance());
        assertEq(token.balanceOf(address(vault)), vault.recognizedAssets());
    }

    function testDirectOpenRelayTopUpDirectCloseAtomicAssets() public {
        _direct(_report(T.OPEN, 100e8));
        assertEq(rules.executor(), address(controller)); assertEq(vault.controller(), address(controller));
        assertEq(controller.positionOwner(POS), owner); assertEq(vault.lockedBalance(owner), 100e18);
        T.Report memory top = _report(T.TOP_UP, 101e8); top.collateralAfter = 150e18;
        _relay(top);
        assertEq(vault.lockedBalance(owner), 150e18);
        _direct(_report(T.CLOSE, 110e8));
        assertEq(uint256(rules.getPosition(POS).status), uint256(E.Status.CLOSED));
        assertEq(vault.freeBalance(owner), 10100e18); assertEq(vault.lockedBalance(owner), 0);
        assertEq(vault.houseBalance(), 900e18); assertEq(controller.nonces(owner), 3);
        _assertCash();
    }
    function testLongShortAllLeveragesExactQuantityAndSettlement() public {
        for (uint8 side; side < 2; ++side) {
            for (uint8 k; k < 3; ++k) {
                uint8 leverage = k == 0 ? 2 : k == 1 ? 5 : 10;
                T.Report memory r = _report(T.OPEN, 100e8);
                r.positionId = keccak256(abi.encode(side, leverage)); r.side = side; r.leverage = leverage;
                r.quantity = M.quantity(100e18, leverage, 100e8);
                _relay(r);
                assertEq(rules.getPosition(r.positionId).quantity, uint256(leverage) * 1e18);
                T.Report memory close = r; close.operationId = bytes32(++serial); close.action = T.CLOSE;
                close.expectedVersion = 1; close.collateralBefore = 100e18;
                close.priceE8 = side == 0 ? 110e8 : 90e8;
                _direct(close);
                assertEq(uint256(rules.getPosition(r.positionId).status), uint256(E.Status.CLOSED));
            }
        }
        _assertCash();
    }
    function testSixtySecondLiquidationAndBadDebtCap() public {
        _relay(_report(T.OPEN, 100e8)); _risk(_report(T.START_CALL, 80e8));
        uint256 deadline = rules.getPosition(POS).deadline;
        assertEq(deadline, block.timestamp + 60);
        vm.warp(deadline - 1);
        T.Report memory tooEarly = _report(T.LIQUIDATE, 80e8);
        bytes memory s = _sign(ORACLE_KEY, rules.reportDigest(tooEarly));
        vm.expectRevert(E.GraceNotOver.selector); vm.prank(relayer); controller.liquidate(tooEarly, s);
        vm.warp(deadline); _risk(_report(T.LIQUIDATE, 80e8));
        assertEq(uint256(rules.getPosition(POS).status), uint256(E.Status.LIQUIDATED));
        assertEq(vault.freeBalance(owner), 9900e18); assertEq(vault.houseBalance(), 1100e18);
        assertEq(vault.lockedBalance(owner), 0); assertEq(controller.nonces(owner), 1);
        _assertCash();
    }
    function testRecoveryAndTopUpRecovery() public {
        _direct(_report(T.OPEN, 100e8)); _risk(_report(T.START_CALL, 90e8));
        _risk(_report(T.CLEAR_CALL, 100e8));
        assertEq(rules.getPosition(POS).deadline, 0);
        _risk(_report(T.START_CALL, 90e8));
        T.Report memory top = _report(T.TOP_UP, 90e8); top.collateralAfter = 200e18; _relay(top);
        assertEq(uint256(rules.getPosition(POS).status), uint256(E.Status.ACTIVE));
        assertEq(rules.getPosition(POS).deadline, 0); assertEq(vault.lockedBalance(owner), 200e18);
    }
    function testSharedNonceCancelRejectsPreviouslySignedRelay() public {
        T.Report memory r = _report(T.OPEN, 100e8); U.Intent memory i = _intent(r);
        bytes memory s = _sign(ORACLE_KEY, rules.reportDigest(r));
        bytes memory u = _sign(USER_KEY, controller.intentDigest(i));
        vm.prank(owner); controller.cancelNonce(0);
        vm.expectRevert(C.InvalidNonce.selector); vm.prank(relayer); controller.executeRelayed(r, s, i, u);
        assertEq(rules.getPosition(POS).version, 0); assertEq(vault.totalLocked(), 0);
    }
    function testDirectThenOldRelayAndRelayThenOldDirectCannotReplay() public {
        T.Report memory r = _report(T.OPEN, 100e8); U.Intent memory i = _intent(r);
        bytes memory s = _sign(ORACLE_KEY, rules.reportDigest(r));
        bytes memory u = _sign(USER_KEY, controller.intentDigest(i));
        vm.prank(owner); controller.executeDirect(r, s, i);
        vm.expectRevert(C.InvalidNonce.selector); vm.prank(relayer); controller.executeRelayed(r, s, i, u);
        T.Report memory close = _report(T.CLOSE, 100e8); U.Intent memory ci = _intent(close);
        bytes memory cs = _sign(ORACLE_KEY, rules.reportDigest(close));
        bytes memory cu = _sign(USER_KEY, controller.intentDigest(ci));
        vm.prank(relayer); controller.executeRelayed(close, cs, ci, cu);
        vm.expectRevert(C.InvalidNonce.selector); vm.prank(owner); controller.executeDirect(close, cs, ci);
        assertEq(controller.nonces(owner), 2);
    }
    function testUnsignedRelayOtherOwnerAndUnauthorizedRiskRejected() public {
        T.Report memory r = _report(T.OPEN, 100e8); U.Intent memory i = _intent(r);
        bytes memory s = _sign(ORACLE_KEY, rules.reportDigest(r));
        vm.expectRevert(); vm.prank(relayer); controller.executeRelayed(r, s, i, "");
        bytes memory wrong = _sign(OTHER_KEY, controller.intentDigest(i));
        vm.expectRevert(C.InvalidOwner.selector); vm.prank(relayer); controller.executeRelayed(r, s, i, wrong);
        vm.expectRevert(C.Unauthorized.selector); vm.prank(other); controller.executeDirect(r, s, i);
        _direct(r); T.Report memory risk = _report(T.START_CALL, 90e8);
        bytes memory rs = _sign(ORACLE_KEY, rules.reportDigest(risk));
        vm.expectRevert(C.Unauthorized.selector); vm.prank(owner); controller.startMarginCall(risk, rs);
    }
    function testIntentBoundsAndMutationRejected() public {
        T.Report memory r = _report(T.OPEN, 100e8); U.Intent memory i = _intent(r);
        bytes memory s = _sign(ORACLE_KEY, rules.reportDigest(r));
        i.priceMaxE8 = 99e8; i.priceMinE8 = 98e8;
        vm.expectRevert(C.OutsideBounds.selector); vm.prank(owner); controller.executeDirect(r, s, i);
        i = _intent(r); i.quantityMin += 1; i.quantityMax += 1;
        vm.expectRevert(C.OutsideBounds.selector); vm.prank(owner); controller.executeDirect(r, s, i);
        i = _intent(r); i.collateralAfter += 1;
        vm.expectRevert(C.InvalidIntent.selector); vm.prank(owner); controller.executeDirect(r, s, i);
    }
    function testOldIntentWithFreshReportAndIndependentExpiry() public {
        T.Report memory r = _report(T.OPEN, 100e8); U.Intent memory i = _intent(r);
        vm.warp(block.timestamp + 10); r.observedAt = block.timestamp; r.validUntil = block.timestamp + 30;
        bytes memory s = _sign(ORACLE_KEY, rules.reportDigest(r));
        vm.prank(owner); controller.executeDirect(r, s, i);
        T.Report memory close = _report(T.CLOSE, 100e8); U.Intent memory ci = _intent(close);
        bytes memory cs = _sign(ORACLE_KEY, rules.reportDigest(close)); vm.warp(block.timestamp + 31);
        vm.expectRevert(E.InvalidReport.selector); vm.prank(owner); controller.executeDirect(close, cs, ci);
        vm.warp(ci.deadline + 1);
        vm.expectRevert(C.InvalidWindow.selector); vm.prank(owner); controller.executeDirect(close, cs, ci);
    }
    function testWrongChainAndOtherControllerDomainSignaturesRejected() public {
        T.Report memory r = _report(T.OPEN, 100e8); U.Intent memory i = _intent(r);
        bytes memory s = _sign(ORACLE_KEY, rules.reportDigest(r));
        bytes memory u = _sign(USER_KEY, controller.intentDigest(i));
        uint256 original = block.chainid; vm.chainId(original + 1);
        vm.expectRevert(C.InvalidOwner.selector); vm.prank(relayer); controller.executeRelayed(r, s, i, u);
        vm.chainId(original);
        C second = new C(address(token), relayer, vm.addr(ORACLE_KEY), admin, rules.supportedSymbolHash(), rules.policyHash());
        bytes memory foreign = _sign(USER_KEY, second.intentDigest(i));
        vm.expectRevert(C.InvalidOwner.selector); vm.prank(relayer); controller.executeRelayed(r, s, i, foreign);
    }
    function testLegacyV1V2UserSignatureDomainsCannotAuthorizeC() public {
        T.Report memory r = _report(T.OPEN, 100e8); U.Intent memory i = _intent(r);
        bytes memory s = _sign(ORACLE_KEY, rules.reportDigest(r));
        bytes32 domainType = keccak256("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)");
        for (uint256 version = 1; version <= 2; ++version) {
            bytes32 domain = keccak256(abi.encode(domainType,
                keccak256("TokenizedMarketFuturesUserIntent"),
                version == 1 ? keccak256("1") : keccak256("2"), block.chainid, address(controller)));
            bytes32 digest = keccak256(abi.encodePacked("\x19\x01", domain, keccak256(abi.encode(U.TYPE_HASH, i))));
            bytes memory foreign = _sign(USER_KEY, digest);
            vm.expectRevert(C.InvalidOwner.selector); vm.prank(relayer); controller.executeRelayed(r, s, i, foreign);
        }
    }
    function testNoUnsignedInnerEngineOrVaultBypassAndRiskCannotClose() public {
        T.Report memory r = _report(T.OPEN, 100e8); bytes memory s = _sign(ORACLE_KEY, rules.reportDigest(r));
        vm.expectRevert(E.Unauthorized.selector); vm.prank(relayer); rules.open(r, s);
        vm.expectRevert(CMarginVault.UnauthorizedController.selector);
        vm.prank(relayer); vault.lock(r.operationId, POS, owner, 100e18);
        _direct(r);
        T.Report memory close = _report(T.CLOSE, 100e8); bytes memory cs = _sign(ORACLE_KEY, rules.reportDigest(close));
        vm.expectRevert(C.InvalidIntent.selector); vm.prank(relayer); controller.liquidate(close, cs);
        T.Report memory risk = _report(T.LIQUIDATE, 90e8); U.Intent memory i = _intent(risk);
        bytes memory rs = _sign(ORACLE_KEY, rules.reportDigest(risk));
        vm.expectRevert(C.InvalidIntent.selector); vm.prank(owner); controller.executeDirect(risk, rs, i);
        assertEq(vault.totalLocked(), 100e18);
    }
    function testPauseOnlyBlocksOpenAndWithdrawRemainsIndependent() public {
        vm.expectRevert(C.Unauthorized.selector); controller.pauseNewPositions(true);
        vm.prank(admin); controller.pauseNewPositions(true);
        T.Report memory r = _report(T.OPEN, 100e8); U.Intent memory i = _intent(r);
        bytes memory s = _sign(ORACLE_KEY, rules.reportDigest(r));
        vm.expectRevert(C.OpenBlocked.selector); vm.prank(owner); controller.executeDirect(r, s, i);
        vm.prank(owner); vault.withdraw(1e18);
        vm.prank(owner); controller.cancelNonce(0);
        vm.prank(admin); controller.pauseNewPositions(false); _direct(_report(T.OPEN, 100e8));
        vm.prank(admin); controller.pauseNewPositions(true);
        T.Report memory top = _report(T.TOP_UP, 100e8); top.collateralAfter = 101e18; _direct(top);
        _direct(_report(T.CLOSE, 100e8));
        vm.prank(owner); vault.withdraw(1e18); _assertCash();
    }
    function testInsufficientFreeRollsBackRulesNonceOperationAndOwner() public {
        T.Report memory r = _report(T.OPEN, 100e8); r.collateralAfter = 10001e18;
        r.quantity = M.quantity(r.collateralAfter, r.leverage, r.priceE8);
        U.Intent memory i = _intent(r); bytes memory s = _sign(ORACLE_KEY, rules.reportDigest(r));
        vm.expectRevert(CMarginVault.InsufficientFree.selector); vm.prank(owner); controller.executeDirect(r, s, i);
        assertEq(controller.nonces(owner), 0); assertEq(controller.positionOwner(POS), address(0));
        assertEq(rules.getPosition(POS).version, 0); assertFalse(rules.usedOperations(r.operationId));
        assertEq(vault.totalLocked(), 0); _assertCash();
    }
    function testInvalidPriceSignatureRollsBackAll() public {
        T.Report memory r = _report(T.OPEN, 100e8); U.Intent memory i = _intent(r);
        bytes memory s = _sign(OTHER_KEY, rules.reportDigest(r));
        vm.expectRevert(E.Unauthorized.selector); vm.prank(owner); controller.executeDirect(r, s, i);
        assertEq(controller.nonces(owner), 0); assertEq(controller.positionOwner(POS), address(0));
        assertEq(vault.totalLocked(), 0); _assertCash();
    }
    function testProfitShortageClosesCreatesClaimBlocksNewOpenAndRepays() public {
        _direct(_report(T.OPEN, 100e8)); _direct(_report(T.CLOSE, 300e8));
        assertEq(uint256(rules.getPosition(POS).status), uint256(E.Status.CLOSED));
        assertEq(vault.totalLocked(), 0); assertEq(vault.freeBalance(owner), 11000e18);
        assertEq(vault.totalUnpaidProfit(), 1000e18); assertEq(vault.houseBalance(), 0);
        T.Report memory next = _report(T.OPEN, 100e8); next.positionId = keccak256("next-position");
        U.Intent memory i = _intent(next); bytes memory s = _sign(ORACLE_KEY, rules.reportDigest(next));
        vm.expectRevert(C.OpenBlocked.selector); vm.prank(owner); controller.executeDirect(next, s, i);
        token.approve(address(vault), 500e18); vault.fundHouse(500e18);
        vault.processNextClaim(); assertEq(vault.totalUnpaidProfit(), 500e18);
        token.approve(address(vault), 500e18); vault.fundHouse(500e18); vault.processNextClaim();
        assertEq(vault.totalUnpaidProfit(), 0); assertEq(vault.freeBalance(owner), 12000e18);
        _direct(next); _assertCash();
    }

    function hashIntentProbe(U.Intent calldata i) external pure returns (bytes32) { return U.hash(i); }

    function _manualStruct(U.Intent memory i) private pure returns (bytes32) {
        bytes32[22] memory w;
        w[0] = bytes32(uint256(uint160(i.owner))); w[1] = bytes32(uint256(uint160(i.relayer)));
        w[2] = i.operationId; w[3] = i.positionId; w[4] = i.accountRef; w[5] = i.symbolHash;
        w[6] = bytes32(uint256(i.action)); w[7] = bytes32(i.expectedVersion); w[8] = bytes32(i.expectedCallEpoch);
        w[9] = bytes32(uint256(i.side)); w[10] = bytes32(uint256(i.leverage)); w[11] = bytes32(i.entryPriceE8);
        w[12] = bytes32(i.collateralBefore); w[13] = bytes32(i.collateralAfter);
        w[14] = bytes32(i.priceMinE8); w[15] = bytes32(i.priceMaxE8);
        w[16] = bytes32(i.quantityMin); w[17] = bytes32(i.quantityMax); w[18] = bytes32(i.nonce);
        w[19] = bytes32(i.issuedAt); w[20] = bytes32(i.deadline); w[21] = i.policyHash;
        bytes32 typeHash = keccak256("CFuturesIntent(address owner,address relayer,bytes32 operationId,bytes32 positionId,bytes32 accountRef,bytes32 symbolHash,uint8 action,uint256 expectedVersion,uint256 expectedCallEpoch,uint8 side,uint8 leverage,uint256 entryPriceE8,uint256 collateralBefore,uint256 collateralAfter,uint256 priceMinE8,uint256 priceMaxE8,uint256 quantityMin,uint256 quantityMax,uint256 nonce,uint256 issuedAt,uint256 deadline,bytes32 policyHash)");
        return keccak256(abi.encodePacked(typeHash, w));
    }
    function _manualDomain(uint256 chain, address target) private pure returns (bytes32) {
        return keccak256(abi.encode(
            keccak256("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"),
            keccak256("TokenizedMarketCFuturesUserIntent"), keccak256("1"), chain, target));
    }
    function testIndependentGoldenStructAndActualControllerDigest() public {
        U.Intent memory g;
        g.owner = address(0x1111111111111111111111111111111111111111);
        g.relayer = address(0x2222222222222222222222222222222222222222);
        g.operationId = bytes32(uint256(3)); g.positionId = bytes32(uint256(4));
        g.accountRef = bytes32(uint256(5)); g.symbolHash = bytes32(uint256(6));
        g.side = 1; g.leverage = 10; g.collateralAfter = 100e18;
        g.priceMinE8 = 99e8; g.priceMaxE8 = 101e8; g.quantityMin = 9e18; g.quantityMax = 11e18;
        g.nonce = 7; g.issuedAt = 1000; g.deadline = 1120; g.policyHash = bytes32(uint256(8));
        bytes32 structHash = _manualStruct(g);
        assertEq(structHash, 0x08379fb7f965c1760c22f2c0ce8cec533b216d3fb5622bfc38f71c686e68b10f);
        assertEq(this.hashIntentProbe(g), structHash);
        bytes32 domain = _manualDomain(31337, address(0x3333333333333333333333333333333333333333));
        assertEq(domain, 0xa81cf76c7e65e2d4a39e6d9637dcf2f104fb9d94d819bdddfab0f733f3bc2c5a);
        assertEq(keccak256(abi.encodePacked("\x19\x01", domain, structHash)),
            0xb81cb4bf2b9bad10a2613da0ec1df89549be8aaf991f3cf4149ef39ca02a3e67);
        U.Intent memory actual = _intent(_report(T.OPEN, 100e8));
        assertEq(controller.intentDigest(actual), keccak256(abi.encodePacked("\x19\x01",
            _manualDomain(block.chainid, address(controller)), _manualStruct(actual))));
    }
    function _mutate(U.Intent memory i, uint256 field) private pure {
        if (field == 0) i.owner = address(uint160(i.owner) ^ 1);
        else if (field == 1) i.relayer = address(uint160(i.relayer) ^ 1);
        else if (field == 2) i.operationId = bytes32(uint256(i.operationId) ^ 1);
        else if (field == 3) i.positionId = bytes32(uint256(i.positionId) ^ 1);
        else if (field == 4) i.accountRef = bytes32(uint256(i.accountRef) ^ 1);
        else if (field == 5) i.symbolHash = bytes32(uint256(i.symbolHash) ^ 1);
        else if (field == 6) ++i.action;
        else if (field == 7) ++i.expectedVersion;
        else if (field == 8) ++i.expectedCallEpoch;
        else if (field == 9) i.side ^= 1;
        else if (field == 10) ++i.leverage;
        else if (field == 11) ++i.entryPriceE8;
        else if (field == 12) ++i.collateralBefore;
        else if (field == 13) ++i.collateralAfter;
        else if (field == 14) ++i.priceMinE8;
        else if (field == 15) ++i.priceMaxE8;
        else if (field == 16) ++i.quantityMin;
        else if (field == 17) ++i.quantityMax;
        else if (field == 18) ++i.nonce;
        else if (field == 19) ++i.issuedAt;
        else if (field == 20) ++i.deadline;
        else if (field == 21) i.policyHash = bytes32(uint256(i.policyHash) ^ 1);
    }
    function testOriginalRelaySignatureBindsAllTwentyTwoFieldsWithoutStateChanges() public {
        T.Report memory r = _report(T.OPEN, 100e8); U.Intent memory original = _intent(r);
        bytes32 digest = controller.intentDigest(original);
        bytes memory userSignature = _sign(USER_KEY, digest);
        bytes memory priceSignature = _sign(ORACLE_KEY, rules.reportDigest(r));
        uint256 ownerFree = vault.freeBalance(owner); uint256 otherFree = vault.freeBalance(other);
        uint256 house = vault.houseBalance();
        for (uint256 field; field < 22; ++field) {
            U.Intent memory changed = abi.decode(abi.encode(original), (U.Intent));
            _mutate(changed, field);
            assertTrue(controller.intentDigest(changed) != digest);
            assertEq(controller.intentDigest(original), digest);
            vm.expectRevert(); vm.prank(relayer);
            controller.executeRelayed(r, priceSignature, changed, userSignature);
            assertEq(vault.freeBalance(owner), ownerFree); assertEq(vault.freeBalance(other), otherFree);
            assertEq(vault.houseBalance(), house); assertEq(vault.totalLocked(), 0);
            assertEq(controller.nonces(owner), 0); assertEq(controller.nonces(other), 0);
            assertEq(controller.positionOwner(POS), address(0)); assertEq(rules.getPosition(POS).version, 0);
            assertFalse(rules.usedOperations(r.operationId)); assertFalse(vault.usedOperations(r.operationId));
            assertEq(vault.totalUnpaidProfit(), 0); _assertCash();
        }
    }
    function testOtherOwnersValidApprovalCannotTopUpOrCloseExistingPosition() public {
        _direct(_report(T.OPEN, 100e8));
        for (uint256 k; k < 2; ++k) {
            T.Report memory r = _report(k == 0 ? T.TOP_UP : T.CLOSE, 100e8);
            if (k == 0) r.collateralAfter = 150e18;
            U.Intent memory i = _intent(r); i.owner = other;
            i.accountRef = controller.accountRef(other); i.nonce = controller.nonces(other);
            r.accountRef = i.accountRef;
            bytes memory s = _sign(ORACLE_KEY, rules.reportDigest(r));
            bytes memory u = _sign(OTHER_KEY, controller.intentDigest(i));
            vm.expectRevert(C.InvalidOwner.selector); vm.prank(relayer); controller.executeRelayed(r, s, i, u);
            vm.expectRevert(C.InvalidOwner.selector); vm.prank(other); controller.executeDirect(r, s, i);
            assertEq(controller.nonces(owner), 1); assertEq(controller.nonces(other), 0);
            assertEq(vault.lockedBalance(owner), 100e18); assertEq(vault.freeBalance(other), 10000e18);
            assertEq(rules.getPosition(POS).version, 1); assertFalse(rules.usedOperations(r.operationId));
            assertFalse(vault.usedOperations(r.operationId)); _assertCash();
        }
    }
    function testUserCancellationDoesNotBlockClearCallOrSubsequentLiquidation() public {
        _direct(_report(T.OPEN, 100e8)); vm.prank(owner); controller.cancelNonce(1);
        _risk(_report(T.START_CALL, 90e8)); _risk(_report(T.CLEAR_CALL, 100e8));
        _risk(_report(T.START_CALL, 80e8));
        uint256 deadline = rules.getPosition(POS).deadline;
        vm.warp(deadline); _risk(_report(T.LIQUIDATE, 80e8));
        assertEq(controller.nonces(owner), 2);
        assertEq(uint256(rules.getPosition(POS).status), uint256(E.Status.LIQUIDATED));
        assertEq(rules.getPosition(POS).callEpoch, 2); assertEq(rules.getPosition(POS).version, 5);
        assertEq(vault.lockedBalance(owner), 0); assertEq(vault.freeBalance(owner), 9900e18);
        assertEq(vault.houseBalance(), 1100e18); assertEq(vault.totalUnpaidProfit(), 0); _assertCash();
    }
}
