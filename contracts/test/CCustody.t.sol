// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Test} from "forge-std/Test.sol";
import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import {ReentrancyGuard} from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import {CMockCollateral} from "../src/CMockCollateral.sol";
import {CMarginVault} from "../src/CMarginVault.sol";

/// @dev Deliberately non-standard test asset, never a C deployment collateral.
contract CAdversarialAsset is ERC20 {
    enum Fault { NONE, FALSE_RETURN, REVERT_CALL, FEE, INFLATION, NO_DEBIT, EXTRA_DEBIT, NO_RETURN }
    Fault public inbound;
    Fault public outbound;
    CMarginVault public callbackVault;
    bytes public callbackData;
    bool public callbackAttempted;
    bool public callbackSucceeded;
    bytes4 public callbackError;

    constructor() ERC20("Adversarial test only", "BAD") { _mint(msg.sender, 1_000_000 ether); }
    function configure(Fault i, Fault o) external { inbound = i; outbound = o; }
    function destroy(address owner, uint256 value) external { _burn(owner, value); }
    function callback(CMarginVault v, bytes calldata data) external { callbackVault = v; callbackData = data; }
    function transferFrom(address from, address to, uint256 value) public override returns (bool) {
        _spendAllowance(from, msg.sender, value);
        return _move(from, to, value, inbound);
    }
    function transfer(address to, uint256 value) public override returns (bool) {
        return _move(msg.sender, to, value, outbound);
    }
    function _move(address from, address to, uint256 value, Fault fault) private returns (bool) {
        if (fault == Fault.FALSE_RETURN) return false;
        if (fault == Fault.REVERT_CALL) revert("TEST_TRANSFER_FAILURE");
        if (address(callbackVault) != address(0)) {
            callbackAttempted = true;
            (bool ok, bytes memory data) = address(callbackVault).call(callbackData);
            callbackSucceeded = ok;
            if (data.length >= 4) callbackError = bytes4(data);
        }
        if (fault == Fault.NO_DEBIT) _mint(to, value);
        else {
            _transfer(from, to, fault == Fault.FEE ? value - 1 : value);
            if (fault == Fault.FEE || fault == Fault.EXTRA_DEBIT) _burn(from, 1);
            if (fault == Fault.INFLATION) _mint(to, 1);
        }
        if (fault == Fault.NO_RETURN) assembly { return(0, 0) }
        return true;
    }
}

contract CVaultFactory {
    function create(address token) external returns (CMarginVault) { return new CMarginVault(token); }
}

contract CCustodyTest is Test {
    CMockCollateral internal token;
    CMarginVault internal vault;
    address internal alice = address(0xA11CE);
    address internal bob = address(0xB0B);
    event Deposited(address indexed owner, uint256 amount);
    event Withdrawn(address indexed owner, uint256 amount);
    event HouseFunded(address indexed contributor, uint256 amount);

    function setUp() public {
        token = new CMockCollateral(address(this));
        vault = new CMarginVault(address(token));
        token.transfer(alice, 1_000_000 ether);
        token.transfer(bob, 1_000_000 ether);
        vm.prank(alice); token.approve(address(vault), 1_000_000 ether);
        vm.prank(bob); token.approve(address(vault), 1_000_000 ether);
        token.approve(address(vault), 10_000_000 ether);
    }

    function testFixedSupplyMetadataAndTreasuryOnly() public view {
        assertEq(token.name(), "TMS C Mock Collateral");
        assertEq(token.symbol(), "cMKRW"); assertEq(token.decimals(), 18);
        assertEq(token.totalSupply(), 20_000_000 ether);
        assertEq(token.balanceOf(address(this)), 18_000_000 ether);
    }
    function testZeroTreasuryRejected() public {
        vm.expectRevert(CMockCollateral.InvalidTreasury.selector); new CMockCollateral(address(0));
    }
    function testNoMintBurnMinterOwnerUpgradeEntrypoints() public {
        bytes[6] memory calls = [abi.encodeWithSignature("mint(address,uint256)", alice, 1),
            abi.encodeWithSignature("burn(address,uint256)", alice, 1),
            abi.encodeWithSignature("setMinter(address)", alice),
            abi.encodeWithSignature("transferOwnership(address)", alice),
            abi.encodeWithSignature("upgradeTo(address)", alice), abi.encodeWithSignature("faucet()")];
        for (uint256 i; i < calls.length; ++i) {
            (bool ok,) = address(token).call(calls[i]);
            assertFalse(ok);
        }
        assertEq(token.totalSupply(), token.FIXED_SUPPLY());
    }
    function testInvalidAssetZeroAndNoCodeRejected() public {
        vm.expectRevert(CMarginVault.InvalidAsset.selector); new CMarginVault(address(0));
        vm.expectRevert(CMarginVault.InvalidAsset.selector); new CMarginVault(alice);
    }
    function testControllerBoundToActualDeployerNotTransactionOrigin() public {
        CVaultFactory factory = new CVaultFactory();
        CMarginVault fresh = factory.create(address(token));
        assertEq(fresh.controller(), address(factory)); assertEq(address(fresh.asset()), address(token));
        assertEq(vault.controller(), address(this));
    }
    function testApproveAloneCreatesNoVaultCredit() public view {
        assertEq(vault.freeBalance(alice), 0); assertEq(vault.recognizedAssets(), 0);
        assertEq(token.balanceOf(address(vault)), 0);
    }
    function testDepositCreditsOnlyCallerAndEmitsActualAmount() public {
        vm.expectEmit(true, false, false, true, address(vault)); emit Deposited(alice, 100 ether);
        vm.prank(alice); vault.deposit(100 ether);
        assertEq(vault.freeBalance(alice), 100 ether); assertEq(vault.freeBalance(bob), 0);
        assertEq(token.balanceOf(alice), 999_900 ether); _conserved();
    }
    function testDepositWithoutAllowanceRevertsWithoutCredit() public {
        address stranger = address(0x123);
        token.transfer(stranger, 100 ether);
        vm.prank(stranger); vm.expectRevert(); vault.deposit(100 ether);
        assertEq(vault.freeBalance(stranger), 0); _conserved();
    }
    function testDepositWithoutActualAssetsReverts() public {
        vm.startPrank(address(0x123)); token.approve(address(vault), 100 ether);
        vm.expectRevert(); vault.deposit(100 ether); vm.stopPrank(); _conserved();
    }
    function testZeroAmountsRejectedWithoutChange() public {
        vm.expectRevert(CMarginVault.ZeroAmount.selector); vault.deposit(0);
        vm.expectRevert(CMarginVault.ZeroAmount.selector); vault.withdraw(0);
        vm.expectRevert(CMarginVault.ZeroAmount.selector); vault.fundHouse(0); _conserved();
    }
    function testWithdrawOwnFreeToOwnWalletWithoutServerOracleOrController() public {
        vm.prank(alice); vault.deposit(100 ether);
        vm.expectEmit(true, false, false, true, address(vault)); emit Withdrawn(alice, 40 ether);
        vm.prank(alice); vault.withdraw(40 ether);
        assertEq(vault.freeBalance(alice), 60 ether); assertEq(token.balanceOf(alice), 999_940 ether); _conserved();
    }
    function testOtherUserCannotWithdrawAliceFree() public {
        vm.prank(alice); vault.deposit(100 ether);
        vm.prank(bob); vm.expectRevert(CMarginVault.InsufficientFree.selector); vault.withdraw(1);
        assertEq(vault.freeBalance(alice), 100 ether); _conserved();
    }
    function testControllerCannotWithdrawUserFreeOrSweep() public {
        vm.prank(alice); vault.deposit(100 ether);
        vm.expectRevert(CMarginVault.InsufficientFree.selector); vault.withdraw(100 ether);
        (bool ok,) = address(vault).call(abi.encodeWithSignature("sweep(address,uint256)", address(this), 100 ether));
        assertFalse(ok); assertEq(vault.freeBalance(alice), 100 ether); _conserved();
    }
    function testWithdrawBeyondFreeRejected() public {
        vm.prank(alice); vault.deposit(1);
        vm.prank(alice); vm.expectRevert(CMarginVault.InsufficientFree.selector); vault.withdraw(2); _conserved();
    }
    function testHouseRequiresOwnActualTransferAndEvent() public {
        vm.expectEmit(true, false, false, true, address(vault)); emit HouseFunded(address(this), 10_000_000 ether);
        vault.fundHouse(10_000_000 ether);
        assertEq(vault.houseBalance(), 10_000_000 ether); assertEq(vault.totalFree(), 0); _conserved();
    }
    function testHouseFundingDoesNotConsumeOtherUsersAllowance() public {
        address stranger = address(0x123);
        vm.prank(stranger); vm.expectRevert(); vault.fundHouse(100 ether);
        assertEq(token.balanceOf(alice), 1_000_000 ether); assertEq(vault.houseBalance(), 0);
    }
    function testHouseIsNotWithdrawableEvenByContributorController() public {
        vault.fundHouse(100 ether);
        vm.expectRevert(CMarginVault.InsufficientFree.selector); vault.withdraw(1);
        (bool ok,) = address(vault).call(abi.encodeWithSignature("withdrawHouse(uint256)", 1)); assertFalse(ok); _conserved();
    }
    function testDirectDonationIsUnassignedNotUserOrHouseCredit() public {
        token.transfer(address(vault), 31);
        assertEq(vault.unassignedAssets(), 31); assertEq(vault.recognizedAssets(), 0);
        vm.prank(alice); vault.deposit(100);
        vm.prank(alice); vault.withdraw(100);
        assertEq(vault.unassignedAssets(), 31); assertEq(vault.houseBalance(), 0); _conserved();
    }
    // Reject legacy unscoped selectors; actual C2 hook authority is tested separately.
    function testNoLegacyUnscopedLockSettleOrControllerSetter() public {
        (bool a,) = address(vault).call(abi.encodeWithSignature("setController(address)", alice));
        (bool b,) = address(vault).call(abi.encodeWithSignature("lock(address,uint256)", alice, 1));
        (bool c,) = address(vault).call(abi.encodeWithSignature("settle(address,uint256)", alice, 1));
        assertFalse(a); assertFalse(b); assertFalse(c);
        assertEq(vault.totalLocked(), 0); assertEq(vault.lockedBalance(alice), 0);
    }
    function testDepositFalseReturnRollback() public { _badInbound(CAdversarialAsset.Fault.FALSE_RETURN, false); }
    function testDepositRevertRollback() public { _badInbound(CAdversarialAsset.Fault.REVERT_CALL, false); }
    function testDepositFeeRollback() public { _badInbound(CAdversarialAsset.Fault.FEE, false); }
    function testDepositInflationRollback() public { _badInbound(CAdversarialAsset.Fault.INFLATION, false); }
    function testDepositMintInsteadOfSenderDebitRollback() public { _badInbound(CAdversarialAsset.Fault.NO_DEBIT, false); }
    function testDepositExtraSenderDebitRollback() public { _badInbound(CAdversarialAsset.Fault.EXTRA_DEBIT, false); }
    function testHouseFaultyReceiptRollback() public { _badInbound(CAdversarialAsset.Fault.FEE, true); }
    function testWithdrawAllTransferFaultsRollback() public {
        for (uint256 f = 1; f <= 6; ++f) {
            (CAdversarialAsset bad, CMarginVault v) = _adversarial();
            v.deposit(100 ether);
            bad.configure(CAdversarialAsset.Fault.NONE, CAdversarialAsset.Fault(f));
            vm.expectRevert(); v.withdraw(50 ether);
            assertEq(v.freeBalance(address(this)), 100 ether); assertEq(v.totalFree(), 100 ether);
            assertEq(bad.balanceOf(address(v)), 100 ether); assertEq(bad.balanceOf(address(this)), 999_900 ether);
        }
    }
    function testOptionalReturnExactTransfersSupported() public {
        (CAdversarialAsset bad, CMarginVault v) = _adversarial();
        bad.configure(CAdversarialAsset.Fault.NO_RETURN, CAdversarialAsset.Fault.NO_RETURN);
        v.deposit(100 ether); v.fundHouse(5 ether); v.withdraw(100 ether);
        assertEq(v.totalFree(), 0); assertEq(v.houseBalance(), 5 ether); assertEq(v.recognizedAssets(), bad.balanceOf(address(v)));
    }
    function testReentrantDepositWithdrawHouseAllBlocked() public {
        bytes[3] memory calls = [abi.encodeCall(vault.deposit, (1)), abi.encodeCall(vault.withdraw, (1)), abi.encodeCall(vault.fundHouse, (1))];
        for (uint256 outer; outer < 3; ++outer) {
            for (uint256 inner; inner < 3; ++inner) {
                (CAdversarialAsset bad, CMarginVault v) = _adversarial();
                v.deposit(100 ether); bad.callback(v, calls[inner]);
                if (outer == 0) v.deposit(10 ether);
                else if (outer == 1) v.withdraw(10 ether);
                else v.fundHouse(10 ether);
                assertTrue(bad.callbackAttempted()); assertFalse(bad.callbackSucceeded());
                assertEq(bad.callbackError(), ReentrancyGuard.ReentrancyGuardReentrantCall.selector);
                assertEq(v.recognizedAssets(), bad.balanceOf(address(v)));
            }
        }
    }
    function testPreexistingAssetDeficitIsExplicitAndFailsClosed() public {
        (CAdversarialAsset bad, CMarginVault v) = _adversarial(); v.deposit(100 ether);
        bad.destroy(address(v), 1);
        vm.expectRevert(CMarginVault.AssetDeficit.selector); v.unassignedAssets();
        vm.expectRevert(CMarginVault.AssetDeficit.selector); v.deposit(1);
        vm.expectRevert(CMarginVault.AssetDeficit.selector); v.fundHouse(1);
        vm.expectRevert(CMarginVault.AssetDeficit.selector); v.withdraw(1);
        assertEq(v.totalFree(), 100 ether);
    }
    function testFuzzMultiOwnerConservation(uint96 a, uint96 b, uint96 h, uint96 withdrawA) public {
        uint256 aa = bound(uint256(a), 1, 1_000_000 ether);
        uint256 bb = bound(uint256(b), 1, 1_000_000 ether);
        uint256 hh = bound(uint256(h), 1, 10_000_000 ether);
        vm.prank(alice); vault.deposit(aa); vm.prank(bob); vault.deposit(bb); vault.fundHouse(hh);
        uint256 out = bound(uint256(withdrawA), 1, aa);
        vm.prank(alice); vault.withdraw(out);
        assertEq(vault.freeBalance(alice), aa - out); assertEq(vault.freeBalance(bob), bb);
        assertEq(vault.totalFree(), aa + bb - out); assertEq(vault.houseBalance(), hh);
        assertEq(token.balanceOf(address(this)) + token.balanceOf(alice) + token.balanceOf(bob)
            + token.balanceOf(address(vault)), token.FIXED_SUPPLY()); _conserved();
    }

    function _adversarial() private returns (CAdversarialAsset bad, CMarginVault v) {
        bad = new CAdversarialAsset(); v = new CMarginVault(address(bad));
        bad.approve(address(v), type(uint256).max);
    }
    function _badInbound(CAdversarialAsset.Fault f, bool house) private {
        (CAdversarialAsset bad, CMarginVault v) = _adversarial();
        bad.approve(address(v), 100 ether); bad.configure(f, CAdversarialAsset.Fault.NONE);
        vm.expectRevert(); if (house) v.fundHouse(100 ether); else v.deposit(100 ether);
        assertEq(v.recognizedAssets(), 0); assertEq(v.totalFree(), 0); assertEq(v.houseBalance(), 0);
        assertEq(bad.balanceOf(address(v)), 0); assertEq(bad.balanceOf(address(this)), 1_000_000 ether);
        assertEq(bad.allowance(address(this), address(v)), 100 ether);
    }
    function _conserved() private view {
        assertEq(vault.recognizedAssets(), vault.totalFree() + vault.totalLocked() + vault.houseBalance());
        assertEq(token.balanceOf(address(vault)), vault.recognizedAssets() + vault.unassignedAssets());
    }
}
