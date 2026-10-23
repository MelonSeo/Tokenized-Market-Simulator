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

/// @notice C3 actual-controller economics with intentionally small WEI collateral.
/// @dev No trusted Vault stub, DB/RPC, real MetaMask, or wall-clock grace claim.
contract CVaultEconomicScenariosTest is Test {
    uint256 constant A_KEY = 5101;
    uint256 constant B_KEY = 5102;
    uint256 constant ORACLE_KEY = 5201;
    bytes32 constant A = keccak256("C3-position-A");
    bytes32 constant B = keccak256("C3-position-B");
    address internal alice;
    address internal bob;
    address internal relayer = address(5301);
    address internal admin = address(5401);
    CMockCollateral internal token;
    C internal controller;
    CMarginVault internal vault;
    E internal rules;
    uint256 internal serial;

    function setUp() public {
        vm.warp(1_000);
        alice = vm.addr(A_KEY); bob = vm.addr(B_KEY);
        token = new CMockCollateral(address(this));
        controller = new C(address(token), relayer, vm.addr(ORACLE_KEY), admin,
            keccak256("mSEC"), keccak256("C-claims-policy-v1"));
        vault = controller.vault(); rules = controller.rules();
        token.transfer(alice, 100_000); token.transfer(bob, 100_000);
        vm.startPrank(alice); token.approve(address(vault), type(uint256).max); vault.deposit(10_000); vm.stopPrank();
        vm.startPrank(bob); token.approve(address(vault), type(uint256).max); vault.deposit(10_000); vm.stopPrank();
        token.approve(address(vault), type(uint256).max);
        // Exactly 50 wei funded house, not the token treasury's entire supply.
        vault.fundHouse(50);
        _user(alice, _report(alice, A, T.OPEN, 100e8, 1_000), true);
        _user(bob, _report(bob, B, T.OPEN, 100e8, 1_000), false);
        assertEq(rules.getPosition(A).quantity, 100);
        assertEq(rules.getPosition(B).quantity, 100);
        _invariants();
    }

    function testActualTwoOwnerCloseClaimsIgnoreNewCashUntilOlderClaimPaid() public {
        _user(alice, _report(alice, A, T.CLOSE, 110e8, 1_000), false);
        assertEq(vault.houseBalance(), 0); assertEq(vault.totalUnpaidProfit(), 950);
        assertEq(uint256(rules.getPosition(A).status), uint256(E.Status.CLOSED));
        assertEq(vault.freeBalance(alice), 10_050);
        vm.prank(alice); vault.withdraw(1_000); // principal remains available despite unpaid profit
        vault.fundHouse(100);
        _user(bob, _report(bob, B, T.TOP_UP, 105e8, 1_100), true);
        assertEq(rules.getPosition(B).quantity, 100);
        _user(bob, _report(bob, B, T.CLOSE, 105e8, 1_100), false);
        assertEq(vault.claims(1).remainingAmount, 950);
        assertEq(vault.claims(2).remainingAmount, 500);
        assertEq(vault.freeBalance(bob), 10_000); // cash 100 does not bypass A
        assertEq(vault.houseBalance(), 100); assertEq(vault.totalLocked(), 0);
        vault.fundHouse(2_000);
        vm.prank(bob); (uint256 id, uint256 paid) = vault.processNextClaim();
        assertEq(id, 1); assertEq(paid, 950); assertEq(vault.headClaimId(), 2);
        assertEq(vault.claims(2).paidAmount, 0); // one call processes one claim despite sufficient cash
        assertEq(vault.freeBalance(alice), 10_000); assertEq(vault.freeBalance(bob), 10_000);
        vm.prank(alice); (id, paid) = vault.processNextClaim();
        assertEq(id, 2); assertEq(paid, 500); assertEq(vault.freeBalance(bob), 10_500);
        assertEq(vault.houseBalance(), 650); _invariants();
    }

    function testOneWeiHeadBoundaryFullRepaymentEmptyNoopAndClaimThreeAppend() public {
        _twoClaims(); // A950, B500, house100
        vault.processNextClaim(); assertEq(vault.claims(1).remainingAmount, 850);
        vault.fundHouse(849); vault.processNextClaim();
        assertEq(vault.headClaimId(), 1); assertEq(vault.claims(1).remainingAmount, 1);
        assertEq(vault.claims(2).paidAmount, 0);
        vault.fundHouse(1); vault.processNextClaim(); assertEq(vault.headClaimId(), 2);
        vault.fundHouse(500); vault.processNextClaim();
        assertEq(vault.totalUnpaidProfit(), 0); assertEq(vault.headClaimId(), 3);
        vm.recordLogs(); (uint256 id, uint256 paid) = vault.processNextClaim();
        assertEq(id, 0); assertEq(paid, 0); assertEq(vm.getRecordedLogs().length, 0);
        bytes32 again = keccak256("C3-A-again");
        _user(alice, _report(alice, again, T.OPEN, 100e8, 1_000), true);
        _user(alice, _report(alice, again, T.CLOSE, 101e8, 1_000), true);
        assertEq(vault.claims(3).originalAmount, 100);
        assertEq(vault.claims(1).paidAmount, 950); assertEq(vault.claims(2).paidAmount, 500);
        assertEq(vault.headClaimId(), 3); assertEq(vault.nextClaimId(), 4); _invariants();
    }

    function testUnpaidAndAdminPauseIndependentlyBlockOnlyNewOpen() public {
        _user(alice, _report(alice, A, T.CLOSE, 110e8, 1_000), true);
        bytes32 again = keccak256("C3-blocked-open");
        _blockedOpen(alice, again);
        vm.prank(admin); controller.pauseNewPositions(true);
        vault.fundHouse(950); vault.processNextClaim(); assertEq(vault.totalUnpaidProfit(), 0);
        _blockedOpen(alice, again); // debt cleared is insufficient while admin pause remains
        vm.prank(admin); controller.pauseNewPositions(false);
        _user(alice, _report(alice, again, T.OPEN, 100e8, 1_000), false);
        assertEq(uint256(rules.getPosition(again).status), uint256(E.Status.ACTIVE)); _invariants();
    }

    function testClaimsAndPauseKeepExistingTopupCloseFundingCancelDepositWithdrawalUsable() public {
        _user(alice, _report(alice, A, T.CLOSE, 110e8, 1_000), false);
        vm.prank(admin); controller.pauseNewPositions(true);
        vm.startPrank(bob); vault.deposit(10); vault.withdraw(10);
        controller.cancelNonce(controller.nonces(bob)); vm.stopPrank();
        _user(bob, _report(bob, B, T.TOP_UP, 100e8, 1_100), true);
        _user(bob, _report(bob, B, T.CLOSE, 105e8, 1_100), true);
        assertEq(vault.totalUnpaidProfit(), 1_450);
        assertEq(uint256(rules.getPosition(B).status), uint256(E.Status.CLOSED));
        vault.fundHouse(1); vault.processNextClaim();
        assertEq(vault.totalUnpaidProfit(), 1_449);
        vm.prank(alice); vault.withdraw(100);
        assertTrue(controller.newPositionsPaused()); _invariants();
    }

    function testRiskRecoveryAndActualBadDebtSettlementRemainAvailableDuringClaimsAndPause() public {
        _user(alice, _report(alice, A, T.CLOSE, 110e8, 1_000), false);
        vm.prank(admin); controller.pauseNewPositions(true);
        uint256 nonceBefore = controller.nonces(bob);
        _risk(_report(bob, B, T.START_CALL, 90e8, 1_000));
        _risk(_report(bob, B, T.CLEAR_CALL, 100e8, 1_000));
        _risk(_report(bob, B, T.START_CALL, 80e8, 1_000));
        uint256 deadline = rules.getPosition(B).deadline;
        vm.warp(deadline - 1); // Foundry timestamp simulation, NOT real 60 seconds
        T.Report memory early = _report(bob, B, T.LIQUIDATE, 80e8, 1_000);
        bytes memory sig = _sign(ORACLE_KEY, rules.reportDigest(early));
        vm.expectRevert(E.GraceNotOver.selector); vm.prank(relayer); controller.liquidate(early, sig);
        vm.warp(deadline);
        M.Mark memory mark = M.mark(0, 100, 100e8, 1_000, 80e8);
        assertEq(mark.realized, -1_000); assertEq(mark.badDebt, 1_000);
        _risk(_report(bob, B, T.LIQUIDATE, 80e8, 1_000));
        assertEq(uint256(rules.getPosition(B).status), uint256(E.Status.LIQUIDATED));
        assertEq(vault.houseBalance(), 1_000); // collateral loss only, NOT loss + badDebt
        assertEq(vault.freeBalance(bob), 9_000); assertEq(controller.nonces(bob), nonceBefore);
        vault.processNextClaim(); assertEq(vault.houseBalance(), 50);
        assertEq(vault.totalUnpaidProfit(), 0); assertEq(vault.freeBalance(alice), 11_000); _invariants();
    }

    function testNormalLossReplenishesHouseButFreshUserDepositAndLockedCollateralDoNot() public {
        _user(alice, _report(alice, A, T.CLOSE, 110e8, 1_000), true);
        vm.prank(bob); vault.deposit(100);
        (, uint256 paid) = vault.processNextClaim(); assertEq(paid, 0);
        assertEq(vault.houseBalance(), 0); assertEq(vault.lockedBalance(bob), 1_000);
        _user(bob, _report(bob, B, T.CLOSE, 99e8, 1_000), false); // actual PnL -100
        assertEq(vault.houseBalance(), 100); assertEq(vault.freeBalance(bob), 10_000);
        vault.processNextClaim(); assertEq(vault.claims(1).remainingAmount, 850);
        assertEq(vault.freeBalance(alice), 10_150); _invariants();
    }

    function testDuplicateEconomicOperationCannotCreateSecondClaimOrCredit() public {
        T.Report memory r = _report(alice, A, T.CLOSE, 110e8, 1_000);
        U.Intent memory intent = _intent(alice, r);
        bytes memory priceSig = _sign(ORACLE_KEY, rules.reportDigest(r));
        bytes memory userSig = _sign(A_KEY, controller.intentDigest(intent));
        vm.prank(relayer); controller.executeRelayed(r, priceSig, intent, userSig);
        uint256 free = vault.freeBalance(alice); uint256 next = vault.nextClaimId();
        vm.expectRevert(C.InvalidNonce.selector); vm.prank(relayer);
        controller.executeRelayed(r, priceSig, intent, userSig);
        assertEq(vault.freeBalance(alice), free); assertEq(vault.nextClaimId(), next);
        assertEq(vault.totalUnpaidProfit(), 950); assertTrue(vault.usedOperations(r.operationId)); _invariants();
    }

    function testUnassignedDonationCannotSatisfyUnpaidProfit() public {
        _user(alice, _report(alice, A, T.CLOSE, 110e8, 1_000), false);
        token.transfer(address(vault), 2_000);
        (, uint256 paid) = vault.processNextClaim(); assertEq(paid, 0);
        assertEq(vault.unassignedAssets(), 2_000); assertEq(vault.totalUnpaidProfit(), 950);
        _blockedOpen(alice, keccak256("donation-not-capital")); _invariants();
    }

    function testFuzzTwoOwnerActualControllerConservesCashAndClaimDebt(uint16 rawA, uint16 rawB, uint16 rawFund) public {
        uint256 priceA = bound(uint256(rawA), 101, 130) * 1e8;
        uint256 priceB = bound(uint256(rawB), 101, 130) * 1e8;
        uint256 funding = bound(uint256(rawFund), 0, 7_000);
        uint256 profitA = (priceA - 100e8) / 1e6;
        uint256 profitB = (priceB - 100e8) / 1e6;
        _user(alice, _report(alice, A, T.CLOSE, priceA, 1_000), false); _invariants();
        _user(bob, _report(bob, B, T.CLOSE, priceB, 1_000), true); _invariants();
        assertEq(vault.claims(2).originalAmount, profitB); // A always exceeds initial H50
        if (funding != 0) vault.fundHouse(funding);
        vault.processNextClaim(); _invariants(); vault.processNextClaim(); _invariants();
        assertEq(vault.totalFree() - 20_000 + vault.totalUnpaidProfit(), profitA + profitB);
        assertEq(vault.totalFree() + vault.houseBalance(), 20_050 + funding);
    }

    function _twoClaims() private {
        _user(alice, _report(alice, A, T.CLOSE, 110e8, 1_000), true);
        vault.fundHouse(100);
        _user(bob, _report(bob, B, T.CLOSE, 105e8, 1_000), false);
        assertEq(vault.totalUnpaidProfit(), 1_450); _invariants();
    }
    function _blockedOpen(address owner, bytes32 pos) private {
        T.Report memory r = _report(owner, pos, T.OPEN, 100e8, 1_000);
        U.Intent memory i = _intent(owner, r); bytes memory sig = _sign(ORACLE_KEY, rules.reportDigest(r));
        uint256 nonce = controller.nonces(owner);
        vm.expectRevert(C.OpenBlocked.selector); vm.prank(owner); controller.executeDirect(r, sig, i);
        assertEq(controller.nonces(owner), nonce); assertFalse(rules.usedOperations(r.operationId));
        assertEq(rules.getPosition(pos).version, 0);
    }
    function _user(address owner, T.Report memory r, bool direct) private {
        U.Intent memory i = _intent(owner, r); bytes memory sig = _sign(ORACLE_KEY, rules.reportDigest(r));
        if (direct) { vm.prank(owner); controller.executeDirect(r, sig, i); }
        else {
            bytes memory userSig = _sign(owner == alice ? A_KEY : B_KEY, controller.intentDigest(i));
            vm.prank(relayer); controller.executeRelayed(r, sig, i, userSig);
        }
    }
    function _risk(T.Report memory r) private {
        bytes memory sig = _sign(ORACLE_KEY, rules.reportDigest(r)); vm.prank(relayer);
        if (r.action == T.START_CALL) controller.startMarginCall(r, sig);
        else if (r.action == T.CLEAR_CALL) controller.clearMarginCall(r, sig);
        else controller.liquidate(r, sig);
    }
    function _report(address owner, bytes32 pos, uint8 action, uint256 price, uint256 collateralAfter)
        private returns (T.Report memory r)
    {
        E.Position memory p = rules.getPosition(pos);
        r.operationId = bytes32(++serial); r.positionId = pos; r.accountRef = controller.accountRef(owner);
        r.symbolHash = rules.supportedSymbolHash(); r.action = action;
        r.expectedVersion = p.version; r.expectedCallEpoch = p.callEpoch; r.side = p.side;
        r.leverage = action == T.OPEN ? 10 : p.leverage;
        r.quantity = action == T.OPEN ? M.quantity(collateralAfter, r.leverage, price) : p.quantity;
        r.entryPriceE8 = action == T.OPEN ? price : p.entryPriceE8;
        r.collateralBefore = p.collateral; r.collateralAfter = collateralAfter;
        r.priceE8 = price; r.observedAt = block.timestamp; r.validUntil = block.timestamp + 30;
        r.policyHash = rules.policyHash(); r.executor = address(controller);
    }
    function _intent(address owner, T.Report memory r) private view returns (U.Intent memory i) {
        i.owner = owner; i.relayer = relayer; i.operationId = r.operationId; i.positionId = r.positionId;
        i.accountRef = r.accountRef; i.symbolHash = r.symbolHash; i.action = r.action;
        i.expectedVersion = r.expectedVersion; i.expectedCallEpoch = r.expectedCallEpoch;
        i.side = r.side; i.leverage = r.leverage; i.entryPriceE8 = r.action == T.OPEN ? 0 : r.entryPriceE8;
        i.collateralBefore = r.collateralBefore; i.collateralAfter = r.collateralAfter;
        i.priceMinE8 = r.priceE8; i.priceMaxE8 = r.priceE8;
        i.quantityMin = r.quantity; i.quantityMax = r.quantity;
        i.nonce = controller.nonces(owner); i.issuedAt = block.timestamp;
        i.deadline = block.timestamp + 120; i.policyHash = r.policyHash;
    }
    function _sign(uint256 key, bytes32 digest) private pure returns (bytes memory) {
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(key, digest); return abi.encodePacked(r, s, v);
    }
    function _invariants() private view {
        assertEq(vault.recognizedAssets(), vault.totalFree() + vault.totalLocked() + vault.houseBalance());
        assertEq(token.balanceOf(address(vault)), vault.recognizedAssets() + vault.unassignedAssets());
        assertEq(vault.totalFree(), vault.freeBalance(alice) + vault.freeBalance(bob));
        assertEq(vault.totalLocked(), vault.lockedBalance(alice) + vault.lockedBalance(bob));
        uint256 unpaid;
        for (uint256 id = 1; id < vault.nextClaimId(); ++id) {
            CMarginVault.Claim memory c = vault.claims(id);
            assertEq(c.originalAmount, c.paidAmount + c.remainingAmount);
            assertTrue(c.owner == alice || c.owner == bob); unpaid += c.remainingAmount;
        }
        assertEq(vault.totalUnpaidProfit(), unpaid);
    }
}
