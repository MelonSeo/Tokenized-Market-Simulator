// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Test} from "forge-std/Test.sol";
import {Vm} from "forge-std/Vm.sol";
import {ECDSA} from "@openzeppelin/contracts/utils/cryptography/ECDSA.sol";
import {UserAuthorizedFuturesEngine as C} from "../src/UserAuthorizedFuturesEngine.sol";
import {FuturesEngine as E} from "../src/FuturesEngine.sol";
import {FuturesReportTypes as T} from "../src/FuturesReportTypes.sol";
import {FuturesUserIntentTypes as U} from "../src/FuturesUserIntentTypes.sol";
import {FuturesMath as M} from "../src/FuturesMath.sol";

contract UserAuthorizedFuturesEngineTest is Test {
    // Public deterministic signing fixtures, not service credentials.
    uint256 constant USER_KEY = 111;
    uint256 constant OTHER_KEY = 222;
    uint256 constant PRICE_KEY = 333;
    address constant RELAYER = address(0xBEEF);
    bytes32 constant SYMBOL = keccak256("mSEC");
    bytes32 constant POLICY = keccak256("isolated-v1-zero-fee-mmr500-grace60");
    C controller;
    E rules;
    address user;
    bytes32 pid = keccak256("position");
    uint256 serial;

    function setUp() public {
        vm.warp(1000);
        user = vm.addr(USER_KEY);
        controller = new C(RELAYER, vm.addr(PRICE_KEY), SYMBOL, POLICY);
        rules = controller.rules();
    }

    function report(uint8 action, uint256 price) internal returns (T.Report memory r) {
        E.Position memory p = rules.getPosition(pid);
        r = T.Report(bytes32(++serial), pid, controller.accountRef(user), SYMBOL, action,
            p.version, p.callEpoch, p.side, p.leverage, p.quantity, p.entryPriceE8,
            p.collateral, p.collateral, price, block.timestamp, block.timestamp + 30, POLICY, address(controller));
    }
    function opening(uint8 side, uint8 leverage) internal returns (T.Report memory r) {
        r = report(T.OPEN, 75000e8);
        r.side = side; r.leverage = leverage; r.collateralAfter = 100000e18;
        r.entryPriceE8 = r.priceE8; r.quantity = M.quantity(r.collateralAfter, leverage, r.priceE8);
    }
    function approval(T.Report memory r) internal view returns (U.Approval memory) {
        return U.Approval(user, controller.nonces(user), r.validUntil, RELAYER);
    }
    function sign(uint256 key, bytes32 digest) internal pure returns (bytes memory) {
        (uint8 v, bytes32 rr, bytes32 ss) = vm.sign(key, digest);
        return abi.encodePacked(rr, ss, v);
    }
    function priceSig(T.Report memory r) internal view returns (bytes memory) {
        return sign(PRICE_KEY, rules.reportDigest(r));
    }
    function dispatch(T.Report memory r, bytes memory s, U.Approval memory a, bytes memory u) internal {
        vm.prank(RELAYER);
        if (r.action == T.OPEN) controller.open(r, s, a, u);
        else if (r.action == T.TOP_UP) controller.topUp(r, s, a, u);
        else if (r.action == T.CLOSE) controller.close(r, s, a, u);
        else if (r.action == T.START_CALL) controller.startMarginCall(r, s);
        else if (r.action == T.CLEAR_CALL) controller.clearMarginCall(r, s);
        else controller.liquidate(r, s);
    }
    function execute(T.Report memory r) internal {
        U.Approval memory a = approval(r);
        dispatch(r, priceSig(r), a, sign(USER_KEY, controller.intentDigest(r, a)));
    }
    function openPosition(uint8 side, uint8 leverage) internal { execute(opening(side, leverage)); }
    function callPosition(uint256 price) internal { execute(report(T.START_CALL, price)); }

    function testCompositionExecutorAndOwnership() public {
        assertEq(rules.executor(), address(controller));
        assertEq(controller.executor(), RELAYER);
        assertEq(rules.priceSigner(), vm.addr(PRICE_KEY));
        T.Report memory r = opening(0, 10);
        execute(r); // Succeeds only because child sees controller, not RELAYER, as msg.sender.
        C.Position memory p = controller.getPosition(pid);
        assertEq(p.owner, user);
        assertEq(p.state.accountRef, controller.accountRef(user));
        assertEq(uint8(p.state.status), uint8(E.Status.ACTIVE));
        assertEq(controller.nonces(user), 1);
    }

    function testNormalOpenTopupCloseAndNonceIndependentOfOperation() public {
        openPosition(0, 5);
        T.Report memory r = report(T.TOP_UP, 75000e8); r.collateralAfter += 10000e18;
        r.operationId = bytes32(uint256(999999)); execute(r);
        assertEq(controller.nonces(user), 2);
        assertTrue(rules.usedOperations(r.operationId));
        execute(report(T.CLOSE, 75750e8));
        assertEq(controller.nonces(user), 3);
        assertEq(uint8(rules.getPosition(pid).status), uint8(E.Status.CLOSED));
        assertEq(controller.positionOwner(pid), user);
        assertEq(rules.activePosition(controller.accountRef(user)), bytes32(0));
    }

    function testMissingUserSignatureAllUserActionsRejected() public {
        T.Report memory r = opening(0, 10);
        bytes memory s = priceSig(r); U.Approval memory a = approval(r);
        vm.expectRevert(abi.encodeWithSelector(ECDSA.ECDSAInvalidSignatureLength.selector, uint256(0)));
        dispatch(r, s, a, "");
        assertEq(controller.positionOwner(pid), address(0));
        openPosition(0, 10);
        r = report(T.TOP_UP, 75000e8); r.collateralAfter += 1;
        s = priceSig(r); a = approval(r);
        vm.expectRevert(abi.encodeWithSelector(ECDSA.ECDSAInvalidSignatureLength.selector, uint256(0)));
        dispatch(r, s, a, "");
        r = report(T.CLOSE, 75000e8);
        s = priceSig(r); a = approval(r);
        vm.expectRevert(abi.encodeWithSelector(ECDSA.ECDSAInvalidSignatureLength.selector, uint256(0)));
        dispatch(r, s, a, "");
        assertEq(controller.nonces(user), 1);
    }

    function testWrongUserAndOwnerMismatchRejected() public {
        T.Report memory r = opening(0, 10); U.Approval memory a = approval(r);
        bytes memory wrong = sign(OTHER_KEY, controller.intentDigest(r, a));
        bytes memory s = priceSig(r);
        vm.expectRevert(C.InvalidUserSignature.selector); dispatch(r, s, a, wrong);
        execute(r);
        r = report(T.CLOSE, 75000e8); a = approval(r); a.owner = vm.addr(OTHER_KEY);
        wrong = sign(OTHER_KEY, controller.intentDigest(r, a));
        s = priceSig(r);
        vm.expectRevert(C.InvalidOwner.selector); dispatch(r, s, a, wrong);
    }

    function testForgedAccountAndNonRelayerRejected() public {
        T.Report memory r = opening(0, 10); r.accountRef = keccak256("server-account");
        U.Approval memory a = approval(r); bytes memory u = sign(USER_KEY, controller.intentDigest(r, a));
        bytes memory s = priceSig(r);
        vm.expectRevert(C.InvalidAccount.selector); dispatch(r, s, a, u);
        r = opening(0, 10); a = approval(r); u = sign(USER_KEY, controller.intentDigest(r, a));
        s = priceSig(r);
        vm.expectRevert(C.UnauthorizedRelayer.selector); controller.open(r, s, a, u);
        a.relayer = user; u = sign(USER_KEY, controller.intentDigest(r, a));
        vm.expectRevert(C.UnauthorizedRelayer.selector); dispatch(r, s, a, u);
    }

    function mutate(T.Report memory r, uint256 n) internal pure {
        if (n == 0) r.operationId = bytes32(uint256(r.operationId) + 1);
        else if (n == 1) r.positionId = keccak256("different-position");
        else if (n == 2) r.accountRef = keccak256("different-account");
        else if (n == 3) r.symbolHash = keccak256("different-symbol");
        else if (n == 4) r.action = T.CLOSE;
        else if (n == 5) ++r.expectedVersion;
        else if (n == 6) ++r.expectedCallEpoch;
        else if (n == 7) r.side = 1;
        else if (n == 8) r.leverage = 5;
        else if (n == 9) ++r.quantity;
        else if (n == 10) ++r.entryPriceE8;
        else if (n == 11) ++r.collateralBefore;
        else if (n == 12) ++r.collateralAfter;
        else if (n == 13) ++r.priceE8;
        else if (n == 14) ++r.observedAt;
        else if (n == 15) ++r.validUntil;
        else if (n == 16) r.policyHash = keccak256("different-policy");
        else r.executor = RELAYER;
    }
    function testAllReportFieldsBoundByUserEvenWhenServerResigns() public {
        for (uint256 n; n < 18; ++n) {
            T.Report memory r = opening(0, 10); U.Approval memory a = approval(r);
            bytes memory u = sign(USER_KEY, controller.intentDigest(r, a));
            mutate(r, n);
            bytes memory s = priceSig(r);
            vm.expectRevert(); vm.prank(RELAYER); controller.open(r, s, a, u);
            assertEq(controller.nonces(user), 0);
            assertEq(controller.positionOwner(r.positionId), address(0));
        }
    }

    function testUserNonceAndSignatureReplayRejected() public {
        T.Report memory r = opening(0, 10); U.Approval memory a = approval(r);
        bytes memory s = priceSig(r); bytes memory u = sign(USER_KEY, controller.intentDigest(r, a));
        dispatch(r, s, a, u);
        vm.expectRevert(C.InvalidUserNonce.selector); dispatch(r, s, a, u);
        r = report(T.TOP_UP, 75000e8); r.collateralAfter += 1;
        a.nonce = 0; u = sign(USER_KEY, controller.intentDigest(r, a));
        s = priceSig(r);
        vm.expectRevert(C.InvalidUserNonce.selector); dispatch(r, s, a, u);
    }

    function testDeadlineAndPriceReportThirtySecondBoundaries() public {
        T.Report memory r = opening(0, 10); U.Approval memory a = approval(r);
        a.deadline = block.timestamp + 10;
        bytes memory u = sign(USER_KEY, controller.intentDigest(r, a)); bytes memory s = priceSig(r);
        vm.warp(a.deadline + 1);
        vm.expectRevert(C.InvalidUserDeadline.selector); dispatch(r, s, a, u);
        r = opening(0, 10); a = approval(r); a.deadline = r.validUntil + 1;
        u = sign(USER_KEY, controller.intentDigest(r, a));
        s = priceSig(r);
        vm.expectRevert(C.InvalidUserDeadline.selector); dispatch(r, s, a, u);
        r = opening(0, 10); a = approval(r); s = priceSig(r); u = sign(USER_KEY, controller.intentDigest(r, a));
        vm.warp(r.validUntil); dispatch(r, s, a, u); // Exact legacy expiry boundary is inclusive.
        r = report(T.CLOSE, 75000e8); a = approval(r); s = priceSig(r); u = sign(USER_KEY, controller.intentDigest(r, a));
        vm.warp(r.validUntil + 1);
        vm.expectRevert(C.InvalidUserDeadline.selector); dispatch(r, s, a, u);
    }

    function testWrongUserDomainChainAndContractRejected() public {
        T.Report memory r = opening(0, 10); U.Approval memory a = approval(r);
        uint256 chain = block.chainid; vm.chainId(chain + 1);
        bytes memory u = sign(USER_KEY, controller.intentDigest(r, a)); vm.chainId(chain);
        bytes memory s = priceSig(r);
        vm.expectRevert(C.InvalidUserSignature.selector); dispatch(r, s, a, u);
        C other = new C(RELAYER, vm.addr(PRICE_KEY), SYMBOL, POLICY);
        u = sign(USER_KEY, other.intentDigest(r, a));
        vm.expectRevert(C.InvalidUserSignature.selector); dispatch(r, s, a, u);
    }

    function testPriceDomainAndExecutorMustUseInnerEngine() public {
        T.Report memory r = opening(0, 10); U.Approval memory a = approval(r);
        bytes memory u = sign(USER_KEY, controller.intentDigest(r, a));
        // User-domain digest cannot serve as the server's price-report digest.
        bytes memory wrong = sign(PRICE_KEY, controller.intentDigest(r, a));
        vm.expectRevert(E.Unauthorized.selector); dispatch(r, wrong, a, u);
        r.executor = RELAYER; a = approval(r); u = sign(USER_KEY, controller.intentDigest(r, a));
        wrong = priceSig(r);
        vm.expectRevert(E.Unauthorized.selector); dispatch(r, wrong, a, u);
        r.executor = address(controller); a = approval(r);
        execute(r);
    }

    function testDirectInnerAndLegacyOuterEntrypointsCannotBypass() public {
        openPosition(0, 10);
        for (uint8 action; action < 6; ++action) {
            T.Report memory r = report(action, 70000e8); bytes memory s = priceSig(r);
            string memory name = action == 0 ? "open" : action == 1 ? "topUp" : action == 2 ? "startMarginCall"
                : action == 3 ? "clearMarginCall" : action == 4 ? "close" : "liquidate";
            bytes memory data = abi.encodeWithSelector(bytes4(keccak256(bytes(string.concat(name,
                "((bytes32,bytes32,bytes32,bytes32,uint8,uint256,uint256,uint8,uint8,uint256,uint256,uint256,uint256,uint256,uint256,uint256,bytes32,address),bytes)")))), r, s);
            vm.prank(RELAYER); (bool ok, bytes memory reason) = address(rules).call(data);
            assertFalse(ok, "direct inner bypass");
            // Prove rejection is the actual permission check, not a mistyped selector or bad state.
            assertEq(reason, abi.encodeWithSelector(E.Unauthorized.selector));
            if (action == 0 || action == 1 || action == 4) {
                vm.prank(RELAYER); (ok,) = address(controller).call(data); assertFalse(ok, "legacy unsigned overload");
            }
        }
        assertEq(controller.nonces(user), 1);
        assertEq(rules.getPosition(pid).version, 1);
    }

    function testRollbackInvalidReportAndDuplicateServerOperation() public {
        T.Report memory r = opening(0, 10); r.quantity += 1;
        U.Approval memory a = approval(r); bytes memory u = sign(USER_KEY, controller.intentDigest(r, a));
        bytes memory s = priceSig(r);
        vm.expectRevert(E.InvalidReport.selector); dispatch(r, s, a, u);
        assertEq(controller.nonces(user), 0); assertEq(controller.positionOwner(pid), address(0));
        assertFalse(rules.usedOperations(r.operationId));
        r = opening(0, 10); bytes32 used = r.operationId; execute(r);
        r = report(T.TOP_UP, 75000e8); r.collateralAfter += 1; r.operationId = used;
        a = approval(r); u = sign(USER_KEY, controller.intentDigest(r, a));
        s = priceSig(r);
        vm.expectRevert(E.AlreadyUsed.selector); dispatch(r, s, a, u);
        assertEq(controller.nonces(user), 1);
    }

    function testBothSidesAutomaticLiquidationAndSixtySecondGrace() public {
        for (uint8 side; side < 2; ++side) {
            pid = bytes32(uint256(side) + 100); openPosition(side, 10);
            uint256 price = side == 0 ? 70000e8 : 80000e8;
            callPosition(price); uint256 deadline = rules.getPosition(pid).deadline;
            assertEq(deadline, block.timestamp + 60);
            vm.warp(deadline - 1); T.Report memory r = report(T.LIQUIDATE, price);
            bytes memory s = priceSig(r); U.Approval memory a = approval(r);
            vm.expectRevert(E.GraceNotOver.selector); dispatch(r, s, a, "");
            vm.warp(deadline + 1);
            vm.expectRevert(E.GraceNotOver.selector); dispatch(r, s, a, ""); // old observation
            execute(report(T.LIQUIDATE, price));
            assertEq(uint8(rules.getPosition(pid).status), uint8(E.Status.LIQUIDATED));
            assertEq(controller.positionOwner(pid), user);
            assertEq(rules.activePosition(controller.accountRef(user)), bytes32(0));
        }
        assertEq(controller.nonces(user), 2); // Automatic actions did not spend user nonces.
    }

    function testRecoveryTopupAndHealthyCannotLiquidate() public {
        openPosition(0, 10); callPosition(70000e8);
        execute(report(T.CLEAR_CALL, 74000e8));
        assertEq(uint8(rules.getPosition(pid).status), uint8(E.Status.ACTIVE));
        assertEq(rules.getPosition(pid).deadline, 0);
        callPosition(70000e8);
        T.Report memory r = report(T.TOP_UP, 70000e8); r.collateralAfter += 100000e18; execute(r);
        assertEq(rules.getPosition(pid).deadline, 0);
        assertEq(controller.nonces(user), 2);
        execute(report(T.CLOSE, 75000e8));
        pid = keccak256("short"); openPosition(1, 10); callPosition(80000e8);
        vm.warp(rules.getPosition(pid).deadline); r = report(T.LIQUIDATE, 76000e8);
        bytes memory s = priceSig(r); U.Approval memory a = approval(r);
        vm.expectRevert(E.RiskConditionNotMet.selector); dispatch(r, s, a, "");
    }

    function testApprovalAndInnerEventsAreLinkedAndNoEscrowClaim() public {
        T.Report memory r = opening(0, 10); U.Approval memory a = approval(r);
        bytes32 digest = controller.intentDigest(r, a);
        vm.recordLogs(); execute(r); Vm.Log[] memory logs = vm.getRecordedLogs();
        assertEq(logs.length, 3);
        assertEq(logs[0].emitter, address(rules)); assertEq(logs[1].emitter, address(rules));
        assertEq(logs[0].topics[1], r.operationId); assertEq(logs[0].topics[2], pid);
        assertEq(logs[1].topics[1], r.operationId);
        assertEq(logs[2].emitter, address(controller)); assertEq(logs[2].topics[1], digest);
        assertEq(logs[2].topics[2], bytes32(uint256(uint160(user)))); assertEq(logs[2].topics[3], pid);
        (uint8 action, uint256 nonce) = abi.decode(logs[2].data, (uint8, uint256));
        assertEq(action, T.OPEN); assertEq(nonce, 0);
        assertEq(address(controller).balance, 0); assertEq(address(rules).balance, 0);
    }

    function testIndependentNestedTypedDataDigest() public {
        T.Report memory r = opening(0, 10); U.Approval memory a = approval(r);
        bytes32 reportType = keccak256("FuturesReport(bytes32 operationId,bytes32 positionId,bytes32 accountRef,bytes32 symbolHash,uint8 action,uint256 expectedVersion,uint256 expectedCallEpoch,uint8 side,uint8 leverage,uint256 quantity,uint256 entryPriceE8,uint256 collateralBefore,uint256 collateralAfter,uint256 priceE8,uint256 observedAt,uint256 validUntil,bytes32 policyHash,address executor)");
        bytes32 nested = keccak256(abi.encode(reportType, r));
        bytes32 userType = keccak256("FuturesUserIntent(address owner,FuturesReport report,uint256 nonce,uint256 deadline,address relayer)FuturesReport(bytes32 operationId,bytes32 positionId,bytes32 accountRef,bytes32 symbolHash,uint8 action,uint256 expectedVersion,uint256 expectedCallEpoch,uint8 side,uint8 leverage,uint256 quantity,uint256 entryPriceE8,uint256 collateralBefore,uint256 collateralAfter,uint256 priceE8,uint256 observedAt,uint256 validUntil,bytes32 policyHash,address executor)");
        bytes32 domain = keccak256(abi.encode(keccak256("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"),
            keccak256("TokenizedMarketFuturesUserIntent"), keccak256("1"), block.chainid, address(controller)));
        bytes32 expected = keccak256(abi.encodePacked(hex"1901", domain,
            keccak256(abi.encode(userType, a.owner, nested, a.nonce, a.deadline, a.relayer))));
        assertEq(controller.intentDigest(r, a), expected);
        assertNotEq(expected, rules.reportDigest(r));
    }

    function testNonEoaOwnerRejectedAndNoUnsignedInitializer() public {
        T.Report memory r = opening(0, 10); U.Approval memory a = approval(r);
        bytes memory s = priceSig(r); bytes memory u = sign(USER_KEY, controller.intentDigest(r, a));
        vm.etch(user, hex"00");
        vm.expectRevert(C.InvalidOwner.selector); dispatch(r, s, a, u);
        vm.etch(user, "");
        vm.prank(RELAYER);
        (bool ok,) = address(controller).call(abi.encodeWithSignature("initialize(address)", RELAYER));
        assertFalse(ok);
        vm.prank(RELAYER);
        (ok,) = address(controller).call(abi.encodeWithSignature("setOwner(bytes32,address)", pid, RELAYER));
        assertFalse(ok);
        assertEq(controller.positionOwner(pid), address(0));
    }

    function testAutomaticPathsRequireRelayerAndCannotDisguiseClose() public {
        openPosition(0, 10);
        T.Report memory r = report(T.START_CALL, 70000e8); bytes memory s = priceSig(r);
        vm.expectRevert(C.UnauthorizedRelayer.selector); controller.startMarginCall(r, s);
        r.action = T.CLEAR_CALL; s = priceSig(r);
        vm.expectRevert(C.UnauthorizedRelayer.selector); controller.clearMarginCall(r, s);
        r.action = T.LIQUIDATE; s = priceSig(r);
        vm.expectRevert(C.UnauthorizedRelayer.selector); controller.liquidate(r, s);
        r = report(T.CLOSE, 75000e8); s = priceSig(r);
        vm.expectRevert(E.InvalidReport.selector); vm.prank(RELAYER); controller.startMarginCall(r, s);
        vm.expectRevert(E.InvalidReport.selector); vm.prank(RELAYER); controller.clearMarginCall(r, s);
        vm.expectRevert(E.InvalidReport.selector); vm.prank(RELAYER); controller.liquidate(r, s);
        assertEq(uint8(rules.getPosition(pid).status), uint8(E.Status.ACTIVE));
        assertEq(controller.nonces(user), 1);
    }

    function testOneActivePositionNoReopenAndOwnerNeverChanges() public {
        openPosition(0, 10); bytes32 original = pid; pid = keccak256("second-active");
        T.Report memory r = opening(0, 10); U.Approval memory a = approval(r);
        bytes memory s = priceSig(r); bytes memory u = sign(USER_KEY, controller.intentDigest(r, a));
        vm.expectRevert(E.InvalidState.selector); dispatch(r, s, a, u);
        assertEq(controller.positionOwner(pid), address(0)); assertEq(controller.nonces(user), 1);
        pid = original; execute(report(T.CLOSE, 75000e8));
        r = opening(0, 10); a = approval(r); s = priceSig(r); u = sign(USER_KEY, controller.intentDigest(r, a));
        vm.expectRevert(C.InvalidOwner.selector); dispatch(r, s, a, u);
        assertEq(controller.positionOwner(pid), user); assertEq(controller.nonces(user), 2);
    }

    function both(E legacy, T.Report memory r) internal {
        T.Report memory old = abi.decode(abi.encode(r), (T.Report));
        old.executor = RELAYER;
        bytes memory oldSignature = sign(PRICE_KEY, legacy.reportDigest(old));
        vm.recordLogs(); execute(r);
        vm.prank(RELAYER);
        if (r.action == T.OPEN) legacy.open(old, oldSignature);
        else legacy.close(old, oldSignature);
        Vm.Log[] memory logs = vm.getRecordedLogs();
        assertEq(logs.length, 5);
        assertEq(logs[0].data, logs[3].data); assertEq(logs[1].data, logs[4].data);
        assertEq(logs[0].topics, logs[3].topics); assertEq(logs[1].topics, logs[4].topics);
        assertEq(abi.encode(rules.getPosition(pid)), abi.encode(legacy.getPosition(pid)));
    }

    function testLongShortAllLeveragesMatchUnmodifiedEngine() public {
        E legacy = new E(RELAYER, vm.addr(PRICE_KEY), SYMBOL, POLICY);
        for (uint8 side; side < 2; ++side) for (uint8 index; index < 3; ++index) {
            uint8 leverage = index == 0 ? 2 : index == 1 ? 5 : 10;
            pid = keccak256(abi.encode(side, leverage));
            both(legacy, opening(side, leverage));
            both(legacy, report(T.CLOSE, side == 0 ? 75750e8 : 74250e8));
        }
    }

    function testFuzzCloseStateAndFinancialEventsMatchLegacy(uint64 priceSeed, uint8 leverageSeed, bool shortSide) public {
        E legacy = new E(RELAYER, vm.addr(PRICE_KEY), SYMBOL, POLICY);
        uint8 n = leverageSeed % 3;
        uint8 leverage = n == 0 ? 2 : n == 1 ? 5 : 10;
        both(legacy, opening(shortSide ? 1 : 0, leverage));
        // Includes gap losses beyond collateral, profits and near-zero mark prices.
        both(legacy, report(T.CLOSE, uint256(priceSeed) % 150000e8 + 1));
    }
}
