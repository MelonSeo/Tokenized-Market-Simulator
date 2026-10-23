// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Test} from "forge-std/Test.sol";
import {ReentrancyGuard} from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import {CMockCollateral} from "../src/CMockCollateral.sol";
import {CMarginVault} from "../src/CMarginVault.sol";
import {CAdversarialAsset} from "./CCustody.t.sol";

/// @dev Intentionally exposes trusted hooks for Vault unit tests only, not deployment.
///      Real controller approval/rules verification is independently tested elsewhere.
contract CClaimsControllerHarness {
    CMarginVault public immutable vault;
    constructor(address token) { vault = new CMarginVault(token); }
    function lock(bytes32 op, bytes32 pos, address owner, uint256 value) external {
        vault.lock(op, pos, owner, value);
    }
    function increase(bytes32 op, bytes32 pos, address owner, uint256 beforeValue, uint256 delta) external {
        vault.increaseLock(op, pos, owner, beforeValue, delta);
    }
    function settle(bytes32 op, bytes32 pos, address owner, uint256 value, int256 pnl, uint256 badDebt)
        external returns (uint256 paid, uint256 claim)
    { return vault.settle(op, pos, owner, value, pnl, badDebt); }
    function settleAndFail(bytes32 op, bytes32 pos, address owner, uint256 value, int256 pnl) external {
        vault.settle(op, pos, owner, value, pnl, 0);
        revert("OUTER_ATOMICITY_TEST");
    }
}

contract CVaultClaimsTest is Test {
    CMockCollateral internal token;
    CClaimsControllerHarness internal controller;
    CMarginVault internal vault;
    address internal alice = address(0xA11CE);
    address internal bob = address(0xB0B);
    bytes32 internal constant A = keccak256("A");
    bytes32 internal constant B = keccak256("B");
    uint256 internal sequence;

    function setUp() public {
        token = new CMockCollateral(address(this));
        controller = new CClaimsControllerHarness(address(token)); vault = controller.vault();
        token.transfer(alice, 1_000_000 ether); token.transfer(bob, 1_000_000 ether);
        vm.startPrank(alice); token.approve(address(vault), type(uint256).max); vault.deposit(1_000); vm.stopPrank();
        vm.startPrank(bob); token.approve(address(vault), type(uint256).max); vault.deposit(1_000); vm.stopPrank();
        token.approve(address(vault), type(uint256).max);
    }

    function testLockMovesOnlyOwnersFreeIntoActualPositionLock() public {
        bytes32 op = _op(); controller.lock(op, A, alice, 100);
        (address owner, uint256 amount, bool terminal) = vault.positionLocks(A);
        assertEq(owner, alice); assertEq(amount, 100); assertFalse(terminal);
        assertEq(vault.freeBalance(alice), 900); assertEq(vault.lockedBalance(alice), 100);
        assertEq(vault.freeBalance(bob), 1_000); assertTrue(vault.usedOperations(op)); _invariants();
    }
    function testEOACreatedVaultCannotExercisePrivilegeEvenAsController() public {
        CMarginVault eoaVault;
        vm.prank(alice); eoaVault = new CMarginVault(address(token));
        vm.prank(alice); vm.expectRevert(CMarginVault.UnauthorizedController.selector);
        eoaVault.lock(_id(1), A, alice, 1);
    }
    function testNonControllerCannotLockIncreaseOrSettleCorrectABI() public {
        vm.expectRevert(CMarginVault.UnauthorizedController.selector); vault.lock(_id(1), A, alice, 1);
        vm.expectRevert(CMarginVault.UnauthorizedController.selector); vault.increaseLock(_id(1), A, alice, 1, 1);
        vm.expectRevert(CMarginVault.UnauthorizedController.selector); vault.settle(_id(1), A, alice, 1, 0, 0);
    }
    function testIncreaseRequiresExactOwnerCollateralAndKeepsQuantityIndependent() public {
        _lock(A, alice, 100); controller.increase(_op(), A, alice, 100, 25);
        (, uint256 amount,) = vault.positionLocks(A); assertEq(amount, 125);
        assertEq(vault.freeBalance(alice), 875); assertEq(vault.lockedBalance(alice), 125); _invariants();
    }
    function testWrongOwnerOrCollateralRejectsAndDoesNotConsumeOperation() public {
        _lock(A, alice, 100); bytes32 op = _op();
        vm.expectRevert(CMarginVault.InvalidPosition.selector); controller.increase(op, A, bob, 100, 1);
        assertFalse(vault.usedOperations(op));
        vm.expectRevert(CMarginVault.StaleCollateral.selector); controller.increase(op, A, alice, 99, 1);
        assertFalse(vault.usedOperations(op));
        vm.expectRevert(CMarginVault.StaleCollateral.selector); controller.settle(op, A, alice, 99, 0, 0);
        assertFalse(vault.usedOperations(op)); _invariants();
    }
    function testInsufficientFreeRollbackAndZeroOrHugeCollateralRejected() public {
        bytes32 op = _op();
        vm.expectRevert(CMarginVault.InsufficientFree.selector); controller.lock(op, A, alice, 1_001);
        assertFalse(vault.usedOperations(op));
        vm.expectRevert(CMarginVault.InvalidSettlement.selector); controller.lock(op, A, alice, 0);
        vm.expectRevert(CMarginVault.InvalidSettlement.selector); controller.lock(op, A, alice, 1e30);
        _lock(A, alice, 100);
        vm.expectRevert(CMarginVault.InvalidSettlement.selector); controller.increase(op, A, alice, 100, 0);
        _invariants();
    }
    function testInvalidIdsOwnersAndDuplicateOperationsRejected() public {
        vm.expectRevert(CMarginVault.InvalidOperation.selector); controller.lock(0, A, alice, 1);
        vm.expectRevert(CMarginVault.InvalidPosition.selector); controller.lock(_id(1), 0, alice, 1);
        vm.expectRevert(CMarginVault.InvalidOwner.selector); controller.lock(_id(1), A, address(0), 1);
        vm.expectRevert(CMarginVault.InvalidOwner.selector); controller.lock(_id(1), A, address(vault), 1);
        bytes32 op = _op(); controller.lock(op, A, alice, 100);
        vm.expectRevert(CMarginVault.AlreadyUsed.selector); controller.increase(op, A, alice, 100, 1);
        vm.expectRevert(CMarginVault.AlreadyUsed.selector); controller.settle(op, A, alice, 100, 0, 0);
        vm.expectRevert(CMarginVault.AlreadyUsed.selector); controller.lock(op, B, bob, 1); _invariants();
    }
    function testPositionOwnerFixedAndIdCannotBeReopenedAfterSettlement() public {
        _lock(A, alice, 100);
        vm.expectRevert(CMarginVault.InvalidPosition.selector); controller.lock(_op(), A, bob, 100);
        _settle(A, alice, 100, 0);
        vm.expectRevert(CMarginVault.InvalidPosition.selector); controller.lock(_op(), A, alice, 100);
        vm.expectRevert(CMarginVault.InvalidPosition.selector); controller.increase(_op(), A, alice, 0, 1);
        vm.expectRevert(CMarginVault.InvalidPosition.selector); controller.settle(_op(), A, alice, 0, 0, 0);
        (address owner, uint256 amount, bool terminal) = vault.positionLocks(A);
        assertEq(owner, alice); assertEq(amount, 0); assertTrue(terminal); _invariants();
    }
    function testLossCreditsOnlyActualCollateralLossNotBadDebt() public {
        _lock(A, alice, 100);
        (uint256 paid, uint256 claim) = controller.settle(_op(), A, alice, 100, -100, 999_999);
        assertEq(paid, 0); assertEq(claim, 0); assertEq(vault.houseBalance(), 100);
        assertEq(vault.freeBalance(alice), 900); assertEq(vault.totalUnpaidProfit(), 0); _invariants();
    }
    function testPartialLossAndZeroPnlCreateNoClaim() public {
        _lock(A, alice, 100); _settle(A, alice, 100, -33);
        _lock(B, bob, 100); _settle(B, bob, 100, 0);
        assertEq(vault.freeBalance(alice), 967); assertEq(vault.freeBalance(bob), 1_000);
        assertEq(vault.houseBalance(), 33); assertEq(vault.nextClaimId(), 1); _invariants();
    }
    function testUncappedLossIntMinAndInconsistentBadDebtRejectedAtomically() public {
        _lock(A, alice, 100); bytes32 op = _op();
        vm.expectRevert(CMarginVault.InvalidSettlement.selector); controller.settle(op, A, alice, 100, -101, 0);
        vm.expectRevert(CMarginVault.InvalidSettlement.selector); controller.settle(op, A, alice, 100, type(int256).min, 0);
        vm.expectRevert(CMarginVault.InvalidSettlement.selector); controller.settle(op, A, alice, 100, 1, 1);
        assertFalse(vault.usedOperations(op)); assertEq(vault.lockedBalance(alice), 100); _invariants();
    }
    function testProfitableCloseHouseSufficientReturnsAllCollateralAndPaidProfit() public {
        vault.fundHouse(80); _lock(A, alice, 100);
        (uint256 paid, uint256 claim) = _settle(A, alice, 100, 50);
        assertEq(paid, 50); assertEq(claim, 0); assertEq(vault.freeBalance(alice), 1_050);
        assertEq(vault.houseBalance(), 30); assertEq(vault.nextClaimId(), 1); _invariants();
    }
    function testHouseExactlyExhaustedCreatesNoZeroClaim() public {
        vault.fundHouse(50); _lock(A, alice, 100);
        (uint256 paid, uint256 claim) = _settle(A, alice, 100, 50);
        assertEq(paid, 50); assertEq(claim, 0); assertEq(vault.houseBalance(), 0);
        assertEq(vault.totalUnpaidProfit(), 0); assertEq(vault.nextClaimId(), 1); _invariants();
    }
    function testShortHouseStillTerminatesAndRecordsOnlyUnpaidRemainder() public {
        vault.fundHouse(20); _lock(A, alice, 100); bytes32 op = _op();
        (uint256 paid, uint256 claim) = controller.settle(op, A, alice, 100, 50, 0);
        assertEq(paid, 20); assertEq(claim, 1); assertEq(vault.freeBalance(alice), 1_020);
        CMarginVault.Claim memory c = vault.claims(claim);
        assertEq(c.owner, alice); assertEq(c.operationId, op); assertEq(c.positionId, A);
        assertEq(c.originalAmount, 30); assertEq(c.paidAmount, 0); assertEq(c.remainingAmount, 30);
        (, uint256 locked, bool terminal) = vault.positionLocks(A); assertEq(locked, 0); assertTrue(terminal); _invariants();
    }
    function testZeroHouseCreatesClaimWithoutTouchingOtherUserCashOrLock() public {
        _lock(A, alice, 100); _lock(B, bob, 300); _settle(A, alice, 100, 50);
        assertEq(vault.freeBalance(alice), 1_000); assertEq(vault.freeBalance(bob), 700);
        assertEq(vault.lockedBalance(bob), 300); assertEq(vault.houseBalance(), 0);
        assertEq(vault.totalUnpaidProfit(), 50); _invariants();
    }
    function testOldClaimCannotBeBypassedByNewProfitAfterHouseReplenishment() public {
        _lock(A, alice, 100); _settle(A, alice, 100, 50); vault.fundHouse(100);
        _lock(B, bob, 100); (uint256 paid, uint256 claim) = _settle(B, bob, 100, 30);
        assertEq(paid, 0); assertEq(claim, 2); assertEq(vault.houseBalance(), 100);
        assertEq(vault.freeBalance(bob), 1_000); assertEq(vault.totalUnpaidProfit(), 80);
        vm.prank(address(0xCA11)); (uint256 head, uint256 value) = vault.processNextClaim();
        assertEq(head, 1); assertEq(value, 50); assertEq(vault.headClaimId(), 2);
        assertEq(vault.claims(2).remainingAmount, 30); // at most one head per call
        assertEq(vault.freeBalance(alice), 1_050); assertEq(vault.freeBalance(bob), 1_000); _invariants();
    }
    function testPartialOneWeiRepaymentAndCallerCannotSelectBeneficiary() public {
        _lock(A, alice, 100); _settle(A, alice, 100, 3); vault.fundHouse(1);
        vm.prank(bob); (uint256 id, uint256 paid) = vault.processNextClaim();
        assertEq(id, 1); assertEq(paid, 1); assertEq(vault.headClaimId(), 1);
        assertEq(vault.freeBalance(alice), 1_001); assertEq(vault.freeBalance(bob), 1_000);
        assertEq(vault.claims(1).paidAmount, 1); assertEq(vault.claims(1).remainingAmount, 2);
        vault.fundHouse(2); vault.processNextClaim();
        assertEq(vault.headClaimId(), 2); assertEq(vault.claims(1).originalAmount, 3);
        assertEq(vault.claims(1).paidAmount, 3); assertEq(vault.claims(1).remainingAmount, 0); _invariants();
    }
    function testEmptyOrZeroHouseProcessingIsNoChangeAndEmitsNoPayment() public {
        vm.recordLogs(); (uint256 id, uint256 paid) = vault.processNextClaim();
        assertEq(id, 0); assertEq(paid, 0); assertEq(vm.getRecordedLogs().length, 0);
        _lock(A, alice, 100); _settle(A, alice, 100, 1);
        vm.recordLogs(); (id, paid) = vault.processNextClaim();
        assertEq(id, 0); assertEq(paid, 0); assertEq(vm.getRecordedLogs().length, 0); _invariants();
    }
    function testRepeatedCallsCannotPayCompletedClaimTwiceAndNewClaimAppends() public {
        _lock(A, alice, 100); _settle(A, alice, 100, 3); vault.fundHouse(3); vault.processNextClaim();
        vault.fundHouse(1); (uint256 id, uint256 paid) = vault.processNextClaim();
        assertEq(id, 0); assertEq(paid, 0); assertEq(vault.freeBalance(alice), 1_003);
        _lock(B, bob, 100); (paid, id) = _settle(B, bob, 100, 3);
        assertEq(paid, 1); assertEq(id, 2); assertEq(vault.headClaimId(), 2);
        vault.fundHouse(2); vault.processNextClaim(); assertEq(vault.headClaimId(), 3); _invariants();
    }
    function testLossReplenishmentPaysOldClaimButNewDepositNeverDoes() public {
        _lock(A, alice, 100); _settle(A, alice, 100, 50);
        vm.prank(bob); vault.deposit(50);
        assertEq(vault.houseBalance(), 0);
        _lock(B, bob, 100); _settle(B, bob, 100, -20);
        vault.processNextClaim(); assertEq(vault.claims(1).remainingAmount, 30);
        assertEq(vault.freeBalance(alice), 1_020); assertEq(vault.freeBalance(bob), 1_030); _invariants();
    }
    function testUnpaidOwnerCanWithdrawOriginalFreeAndReturnedCollateral() public {
        _lock(A, alice, 100); _settle(A, alice, 100, 500);
        uint256 beforeWallet = token.balanceOf(alice);
        vm.prank(alice); vault.withdraw(1_000);
        assertEq(token.balanceOf(alice), beforeWallet + 1_000);
        assertEq(vault.freeBalance(alice), 0); assertEq(vault.claims(1).remainingAmount, 500); _invariants();
    }
    function testOuterFailureRollsBackTerminalOperationAndClaimTogether() public {
        _lock(A, alice, 100); bytes32 op = _op();
        vm.expectRevert(bytes("OUTER_ATOMICITY_TEST")); controller.settleAndFail(op, A, alice, 100, 50);
        (, uint256 amount, bool terminal) = vault.positionLocks(A);
        assertEq(amount, 100); assertFalse(terminal); assertFalse(vault.usedOperations(op));
        assertEq(vault.nextClaimId(), 1); assertEq(vault.totalUnpaidProfit(), 0);
        assertEq(vault.freeBalance(alice), 900); _invariants();
    }
    function testDirectDonationDoesNotFundClaimOrHouse() public {
        _lock(A, alice, 100); _settle(A, alice, 100, 10); token.transfer(address(vault), 1_000);
        (, uint256 paid) = vault.processNextClaim(); assertEq(paid, 0);
        assertEq(vault.unassignedAssets(), 1_000); assertEq(vault.claims(1).remainingAmount, 10); _invariants();
    }
    function testAssetDeficitPreventsAllEconomicHooksAndClaimPayment() public {
        CAdversarialAsset bad = new CAdversarialAsset();
        CClaimsControllerHarness c = new CClaimsControllerHarness(address(bad)); CMarginVault v = c.vault();
        bad.approve(address(v), 100); v.deposit(100); c.lock(_id(1), A, address(this), 50); bad.destroy(address(v), 1);
        vm.expectRevert(CMarginVault.AssetDeficit.selector); c.lock(_id(2), B, address(this), 1);
        vm.expectRevert(CMarginVault.AssetDeficit.selector); c.increase(_id(2), A, address(this), 50, 1);
        vm.expectRevert(CMarginVault.AssetDeficit.selector); c.settle(_id(2), A, address(this), 50, 0, 0);
        vm.expectRevert(CMarginVault.AssetDeficit.selector); v.processNextClaim();
        assertEq(v.lockedBalance(address(this)), 50); assertFalse(v.usedOperations(_id(2)));
    }
    function testDepositCallbackCannotReenterControllerLockBeforeDepositCompletes() public {
        CAdversarialAsset bad = new CAdversarialAsset();
        CClaimsControllerHarness c = new CClaimsControllerHarness(address(bad)); CMarginVault v = c.vault();
        bad.approve(address(v), 200); v.deposit(100);
        bad.callback(CMarginVault(address(c)), abi.encodeCall(c.lock, (_id(1), A, address(this), 1)));
        v.deposit(100);
        assertTrue(bad.callbackAttempted()); assertFalse(bad.callbackSucceeded());
        assertEq(bad.callbackError(), ReentrancyGuard.ReentrancyGuardReentrantCall.selector);
        assertEq(v.totalFree(), 200); assertEq(v.totalLocked(), 0); assertFalse(v.usedOperations(_id(1)));
    }
    function testFuzzCashAndLiabilityConservation(uint64 pnlA, uint64 pnlB, uint64 funding, uint64 repayment) public {
        uint256 a = bound(uint256(pnlA), 1, 1e18); uint256 b = bound(uint256(pnlB), 1, 1e18);
        uint256 h = bound(uint256(funding), 0, 1e18); uint256 r = bound(uint256(repayment), 0, 1e18);
        vault.fundHouse(h + 1); _lock(A, alice, 100); (uint256 paidA,) = _settle(A, alice, 100, int256(a));
        _lock(B, bob, 100); (uint256 paidB,) = _settle(B, bob, 100, int256(b));
        if (r > 0) vault.fundHouse(r);
        vault.processNextClaim(); _invariants();
        assertEq(vault.totalFree() - 2_000, a + b - vault.totalUnpaidProfit());
        assertEq(vault.houseBalance() + vault.totalFree(), 2_000 + h + 1 + r);
        assertLe(paidA + paidB, h + 1);
    }

    function _lock(bytes32 id, address owner, uint256 amount) private { controller.lock(_op(), id, owner, amount); }
    function _settle(bytes32 id, address owner, uint256 collateral, int256 pnl) private returns (uint256, uint256) {
        return controller.settle(_op(), id, owner, collateral, pnl, 0);
    }
    function _op() private returns (bytes32) { return _id(++sequence); }
    function _id(uint256 n) private pure returns (bytes32) { return keccak256(abi.encode("claimtest", n)); }
    function _invariants() private view {
        assertEq(vault.recognizedAssets(), vault.totalFree() + vault.totalLocked() + vault.houseBalance());
        assertEq(token.balanceOf(address(vault)), vault.recognizedAssets() + vault.unassignedAssets());
        assertEq(vault.totalFree(), vault.freeBalance(alice) + vault.freeBalance(bob));
        assertEq(vault.totalLocked(), vault.lockedBalance(alice) + vault.lockedBalance(bob));
        uint256 unpaid;
        for (uint256 i = 1; i < vault.nextClaimId(); ++i) {
            CMarginVault.Claim memory c = vault.claims(i);
            assertEq(c.originalAmount, c.paidAmount + c.remainingAmount); unpaid += c.remainingAmount;
            assertTrue(c.owner == alice || c.owner == bob);
        }
        assertEq(vault.totalUnpaidProfit(), unpaid);
    }
}
