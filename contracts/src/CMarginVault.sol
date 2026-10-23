// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import {ReentrancyGuard} from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";

/// @notice C-only actual collateral custody, not the legacy PostgreSQL mock ledger.
/// @dev C2 adds immutable-controller position accounting and bounded FIFO claims.
///      The controller must verify user approval and rules results in the same tx.
///      The final C deployment must bind the reviewed fixed-supply token identity;
///      accepting an IERC20 address does not make arbitrary assets trustworthy.
contract CMarginVault is ReentrancyGuard {
    using SafeERC20 for IERC20;

    IERC20 public immutable asset;
    address public immutable controller;
    mapping(address => uint256) public freeBalance;
    mapping(address => uint256) public lockedBalance;
    uint256 public totalFree;
    uint256 public totalLocked;
    uint256 public houseBalance;

    struct PositionLock { address owner; uint256 amount; bool terminal; }
    struct Claim {
        address owner;
        bytes32 operationId;
        bytes32 positionId;
        uint256 originalAmount;
        uint256 paidAmount;
        uint256 remainingAmount;
    }
    mapping(bytes32 => PositionLock) public positionLocks;
    mapping(bytes32 => bool) public usedOperations;
    mapping(uint256 => Claim) private claimRecords;
    uint256 public headClaimId = 1;
    uint256 public nextClaimId = 1;
    uint256 public totalUnpaidProfit;

    error InvalidAsset();
    error InvalidOwner();
    error ZeroAmount();
    error InsufficientFree();
    error TransferAmountMismatch();
    error AssetDeficit();
    error UnauthorizedController();
    error InvalidOperation();
    error InvalidPosition();
    error StaleCollateral();
    error InvalidSettlement();
    error AlreadyUsed();

    event Deposited(address indexed owner, uint256 amount);
    event Withdrawn(address indexed owner, uint256 amount);
    event HouseFunded(address indexed contributor, uint256 amount);
    event CollateralLocked(bytes32 indexed operationId, bytes32 indexed positionId,
        address indexed owner, uint256 delta, uint256 lockedAfter);
    event PositionSettled(bytes32 indexed operationId, bytes32 indexed positionId,
        address indexed owner, uint256 collateral, int256 realizedPnl,
        uint256 badDebt, uint256 paidProfit, uint256 claimId);
    event ProfitClaimCreated(uint256 indexed claimId, address indexed owner,
        bytes32 indexed positionId, bytes32 operationId, uint256 amount);
    event ProfitClaimPaid(uint256 indexed claimId, address indexed owner,
        uint256 paidAmount, uint256 remainingAmount);

    modifier onlyController() {
        // Reject an EOA-created Vault's EOA privileged calls as well as outsiders.
        if (msg.sender != controller || controller.code.length == 0) revert UnauthorizedController();
        _;
    }

    constructor(address asset_) {
        if (asset_ == address(0) || asset_.code.length == 0) revert InvalidAsset();
        asset = IERC20(asset_);
        // During a controller constructor its code length is zero. No setter exists.
        controller = msg.sender;
    }

    function deposit(uint256 amount) external nonReentrant {
        _pullOwnTokens(amount);
        freeBalance[msg.sender] += amount;
        totalFree += amount;
        emit Deposited(msg.sender, amount);
    }

    function fundHouse(uint256 amount) external nonReentrant {
        _pullOwnTokens(amount);
        houseBalance += amount;
        emit HouseFunded(msg.sender, amount);
    }

    /// @dev No token transfer: owner free collateral was actually deposited earlier.
    function lock(bytes32 operationId, bytes32 positionId, address owner, uint256 amount)
        external onlyController nonReentrant
    {
        _beginOperation(operationId, positionId, owner);
        if (positionLocks[positionId].owner != address(0)) revert InvalidPosition();
        _validCollateral(amount);
        if (freeBalance[owner] < amount) revert InsufficientFree();
        freeBalance[owner] -= amount; totalFree -= amount;
        lockedBalance[owner] += amount; totalLocked += amount;
        positionLocks[positionId] = PositionLock(owner, amount, false);
        emit CollateralLocked(operationId, positionId, owner, amount, amount);
    }

    function increaseLock(bytes32 operationId, bytes32 positionId, address owner,
        uint256 expectedBefore, uint256 amount) external onlyController nonReentrant
    {
        _beginOperation(operationId, positionId, owner);
        PositionLock storage p = _activeLock(positionId, owner, expectedBefore);
        _validCollateral(amount);
        _validCollateral(expectedBefore + amount);
        if (freeBalance[owner] < amount) revert InsufficientFree();
        freeBalance[owner] -= amount; totalFree -= amount;
        lockedBalance[owner] += amount; totalLocked += amount;
        p.amount += amount;
        emit CollateralLocked(operationId, positionId, owner, amount, p.amount);
    }

    /// @notice Terminal position settlement, independent of profit payment sufficiency.
    /// @dev The immutable controller must match PnL/badDebt to validated rules math.
    function settle(bytes32 operationId, bytes32 positionId, address owner,
        uint256 expectedCollateral, int256 realizedPnl, uint256 badDebt)
        external onlyController nonReentrant returns (uint256 paidProfit, uint256 claimId)
    {
        _beginOperation(operationId, positionId, owner);
        PositionLock storage p = _activeLock(positionId, owner, expectedCollateral);
        // Lock collateral is bounded below int256 max. Check before negating PnL.
        if (realizedPnl < -int256(expectedCollateral)
            || (badDebt != 0 && realizedPnl != -int256(expectedCollateral))) revert InvalidSettlement();
        p.amount = 0; p.terminal = true;
        lockedBalance[owner] -= expectedCollateral; totalLocked -= expectedCollateral;
        if (realizedPnl <= 0) {
            uint256 loss = uint256(-realizedPnl);
            freeBalance[owner] += expectedCollateral - loss;
            totalFree += expectedCollateral - loss;
            houseBalance += loss;
        } else {
            freeBalance[owner] += expectedCollateral; totalFree += expectedCollateral;
            uint256 profit = uint256(realizedPnl);
            if (totalUnpaidProfit == 0) {
                paidProfit = profit < houseBalance ? profit : houseBalance;
                houseBalance -= paidProfit;
                freeBalance[owner] += paidProfit; totalFree += paidProfit;
            }
            uint256 unpaid = profit - paidProfit;
            if (unpaid != 0) {
                claimId = nextClaimId++;
                claimRecords[claimId] = Claim(owner, operationId, positionId, unpaid, 0, unpaid);
                totalUnpaidProfit += unpaid;
                emit ProfitClaimCreated(claimId, owner, positionId, operationId, unpaid);
            }
        }
        // badDebt is an informational excess-loss record, never new cash or debt charged to owner.
        emit PositionSettled(operationId, positionId, owner, expectedCollateral,
            realizedPnl, badDebt, paidProfit, claimId);
    }

    /// @notice Anyone may pay gas to credit exactly one FIFO head owner's free balance.
    /// @dev No external transfer, no beneficiary override, no unbounded queue traversal.
    function processNextClaim() external nonReentrant returns (uint256 claimId, uint256 paidAmount) {
        _assertSolvent();
        if (totalUnpaidProfit == 0 || houseBalance == 0) return (0, 0);
        claimId = headClaimId;
        Claim storage c = claimRecords[claimId];
        paidAmount = c.remainingAmount < houseBalance ? c.remainingAmount : houseBalance;
        c.remainingAmount -= paidAmount; c.paidAmount += paidAmount;
        houseBalance -= paidAmount; totalUnpaidProfit -= paidAmount;
        freeBalance[c.owner] += paidAmount; totalFree += paidAmount;
        if (c.remainingAmount == 0) ++headClaimId;
        emit ProfitClaimPaid(claimId, c.owner, paidAmount, c.remainingAmount);
    }

    function claims(uint256 claimId) external view returns (Claim memory) { return claimRecords[claimId]; }

    /// @notice Withdraw only the caller's free collateral, only to that caller.
    /// @dev Independent of JWT, server, Oracle, and the deploying controller.
    function withdraw(uint256 amount) external nonReentrant {
        _validateAmountOwner(amount);
        if (freeBalance[msg.sender] < amount) revert InsufficientFree();
        uint256 vaultBefore = asset.balanceOf(address(this));
        if (vaultBefore < recognizedAssets()) revert AssetDeficit();
        uint256 ownerBefore = asset.balanceOf(msg.sender);
        freeBalance[msg.sender] -= amount;
        totalFree -= amount;
        asset.safeTransfer(msg.sender, amount);
        uint256 vaultAfter = asset.balanceOf(address(this));
        uint256 ownerAfter = asset.balanceOf(msg.sender);
        if (vaultAfter > vaultBefore || vaultBefore - vaultAfter != amount
            || ownerAfter < ownerBefore || ownerAfter - ownerBefore != amount) {
            revert TransferAmountMismatch();
        }
        emit Withdrawn(msg.sender, amount);
    }

    function recognizedAssets() public view returns (uint256) {
        return totalFree + totalLocked + houseBalance;
    }

    /// @notice Unassigned direct donations are not free collateral or house equity.
    function unassignedAssets() external view returns (uint256) {
        uint256 actual = asset.balanceOf(address(this));
        uint256 recognized = recognizedAssets();
        if (actual < recognized) revert AssetDeficit();
        return actual - recognized;
    }

    function _pullOwnTokens(uint256 amount) private {
        _validateAmountOwner(amount);
        uint256 vaultBefore = asset.balanceOf(address(this));
        if (vaultBefore < recognizedAssets()) revert AssetDeficit();
        uint256 ownerBefore = asset.balanceOf(msg.sender);
        asset.safeTransferFrom(msg.sender, address(this), amount);
        uint256 vaultAfter = asset.balanceOf(address(this));
        uint256 ownerAfter = asset.balanceOf(msg.sender);
        if (vaultAfter < vaultBefore || vaultAfter - vaultBefore != amount
            || ownerAfter > ownerBefore || ownerBefore - ownerAfter != amount) {
            revert TransferAmountMismatch();
        }
    }

    function _validateAmountOwner(uint256 amount) private view {
        if (amount == 0) revert ZeroAmount();
        if (msg.sender == address(0) || msg.sender == address(this)) revert InvalidOwner();
    }

    function _assertSolvent() private view {
        if (asset.balanceOf(address(this)) < recognizedAssets()) revert AssetDeficit();
    }

    function _validCollateral(uint256 amount) private pure {
        if (amount == 0 || amount >= 1e30) revert InvalidSettlement();
    }

    function _beginOperation(bytes32 operationId, bytes32 positionId, address owner) private {
        _assertSolvent();
        if (operationId == bytes32(0)) revert InvalidOperation();
        if (positionId == bytes32(0)) revert InvalidPosition();
        if (owner == address(0) || owner == address(this)) revert InvalidOwner();
        if (usedOperations[operationId]) revert AlreadyUsed();
        usedOperations[operationId] = true;
    }

    function _activeLock(bytes32 positionId, address owner, uint256 expectedBefore)
        private view returns (PositionLock storage p)
    {
        p = positionLocks[positionId];
        if (p.owner != owner || p.terminal || p.amount == 0) revert InvalidPosition();
        if (p.amount != expectedBefore) revert StaleCollateral();
    }
}
