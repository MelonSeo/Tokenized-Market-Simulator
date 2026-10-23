// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {ECDSA} from "@openzeppelin/contracts/utils/cryptography/ECDSA.sol";
import {EIP712} from "@openzeppelin/contracts/utils/cryptography/EIP712.sol";
import {ReentrancyGuard} from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import {FuturesEngine as E} from "./FuturesEngine.sol";
import {FuturesMath as M} from "./FuturesMath.sol";
import {FuturesReportTypes as T} from "./FuturesReportTypes.sol";
import {CFuturesIntentTypes as U} from "./CFuturesIntentTypes.sol";
import {CMarginVault} from "./CMarginVault.sol";

/// @notice C-only user authorization and atomic rules/actual-collateral execution.
/// @dev Oracle attestation is not proof of objectively correct prices or future solvency.
contract CCollateralizedFuturesController is EIP712, ReentrancyGuard {
    uint256 public constant MAX_USER_TTL = 120;
    bytes32 private constant ACCOUNT_DOMAIN = keccak256("TMS_C_FUTURES_WALLET_ACCOUNT_V1");
    address public immutable relayer;
    address public immutable pauseAdmin;
    E public immutable rules;
    CMarginVault public immutable vault;
    bool public newPositionsPaused;
    mapping(address => uint256) public nonces;
    mapping(bytes32 => address) public positionOwner;

    error InvalidConfiguration();
    error Unauthorized();
    error InvalidOwner();
    error InvalidNonce();
    error InvalidWindow();
    error InvalidIntent();
    error InvalidBounds();
    error OutsideBounds();
    error InvalidState();
    error OpenBlocked();

    event CUserIntentExecuted(bytes32 indexed operationId, bytes32 indexed positionId,
        address indexed owner, bytes32 intentDigest, bytes32 reportDigest,
        uint8 action, uint256 nonce, bool direct);
    event CRiskExecuted(bytes32 indexed operationId, bytes32 indexed positionId,
        address indexed owner, bytes32 reportDigest, uint8 action);
    event CNonceCancelled(address indexed owner, uint256 nonce);
    event NewPositionsPauseChanged(bool paused);

    constructor(address asset, address relayer_, address signer, address admin,
        bytes32 symbol, bytes32 policy) EIP712("TokenizedMarketCFuturesUserIntent", "1")
    {
        if (relayer_ == address(0) || signer == address(0) || admin == address(0)
            || relayer_ == signer || relayer_ == admin || signer == admin) revert InvalidConfiguration();
        relayer = relayer_; pauseAdmin = admin;
        rules = new E(address(this), signer, symbol, policy);
        vault = new CMarginVault(asset);
    }

    function accountRef(address owner) public view returns (bytes32) {
        return keccak256(abi.encode(ACCOUNT_DOMAIN, owner, rules.supportedSymbolHash()));
    }
    function intentDigest(U.Intent calldata intent) public view returns (bytes32) {
        return _hashTypedDataV4(U.hash(intent));
    }
    function pauseNewPositions(bool paused) external nonReentrant {
        if (msg.sender != pauseAdmin) revert Unauthorized();
        newPositionsPaused = paused;
        emit NewPositionsPauseChanged(paused);
    }
    function cancelNonce(uint256 expectedNonce) external nonReentrant {
        _owner(msg.sender);
        if (nonces[msg.sender] != expectedNonce) revert InvalidNonce();
        nonces[msg.sender] = expectedNonce + 1;
        emit CNonceCancelled(msg.sender, expectedNonce);
    }
    function executeDirect(T.Report calldata r, bytes calldata s, U.Intent calldata i) external nonReentrant {
        if (msg.sender != i.owner) revert Unauthorized();
        _executeUser(r, s, i, true);
    }
    function executeRelayed(T.Report calldata r, bytes calldata s, U.Intent calldata i,
        bytes calldata userSignature) external nonReentrant
    {
        if (msg.sender != relayer) revert Unauthorized();
        if (ECDSA.recover(intentDigest(i), userSignature) != i.owner) revert InvalidOwner();
        _executeUser(r, s, i, false);
    }
    function startMarginCall(T.Report calldata r, bytes calldata s) external nonReentrant {
        _risk(r, s, T.START_CALL);
    }
    function clearMarginCall(T.Report calldata r, bytes calldata s) external nonReentrant {
        _risk(r, s, T.CLEAR_CALL);
    }
    function liquidate(T.Report calldata r, bytes calldata s) external nonReentrant {
        _risk(r, s, T.LIQUIDATE);
    }

    function _owner(address owner) private view {
        if (owner == address(0) || owner.code.length != 0) revert InvalidOwner();
    }
    function _executeUser(T.Report calldata r, bytes calldata s, U.Intent calldata i, bool direct) private {
        _authorize(r, i);
        nonces[i.owner] = i.nonce + 1;
        if (r.action == T.OPEN) {
            if (newPositionsPaused || vault.totalUnpaidProfit() != 0) revert OpenBlocked();
            if (positionOwner[r.positionId] != address(0)) revert InvalidOwner();
            positionOwner[r.positionId] = i.owner;
        }
        _apply(r, s, i.owner);
        emit CUserIntentExecuted(r.operationId, r.positionId, i.owner, intentDigest(i),
            rules.reportDigest(r), r.action, i.nonce, direct);
    }
    function _authorize(T.Report calldata r, U.Intent calldata i) private view {
        _owner(i.owner);
        if (i.relayer != relayer) revert Unauthorized();
        if (i.nonce != nonces[i.owner]) revert InvalidNonce();
        if (i.issuedAt > block.timestamp || i.deadline <= i.issuedAt
            || i.deadline - i.issuedAt > MAX_USER_TTL || block.timestamp > i.deadline) revert InvalidWindow();
        if (r.action != T.OPEN && r.action != T.TOP_UP && r.action != T.CLOSE) revert InvalidIntent();
        if (r.action != T.OPEN && positionOwner[r.positionId] != i.owner) revert InvalidOwner();
        if (i.operationId != r.operationId || i.positionId != r.positionId || i.action != r.action
            || i.accountRef != accountRef(i.owner) || i.accountRef != r.accountRef
            || i.symbolHash != r.symbolHash || i.policyHash != r.policyHash || r.executor != address(this)
            || i.expectedVersion != r.expectedVersion || i.expectedCallEpoch != r.expectedCallEpoch
            || i.side != r.side || i.leverage != r.leverage || i.collateralBefore != r.collateralBefore
            || i.collateralAfter != r.collateralAfter) revert InvalidIntent();
        if (i.priceMinE8 == 0 || i.priceMinE8 > i.priceMaxE8 || i.priceMaxE8 >= 1e20
            || i.quantityMin == 0 || i.quantityMin > i.quantityMax || i.quantityMax >= 1e30) revert InvalidBounds();
        if (r.priceE8 < i.priceMinE8 || r.priceE8 > i.priceMaxE8
            || r.quantity < i.quantityMin || r.quantity > i.quantityMax) revert OutsideBounds();
        if (r.action == T.OPEN) {
            if (i.entryPriceE8 != 0 || r.entryPriceE8 != r.priceE8
                || r.quantity != M.quantity(r.collateralAfter, r.leverage, r.priceE8)) revert InvalidIntent();
        } else if (i.quantityMin != i.quantityMax || i.entryPriceE8 != r.entryPriceE8) revert InvalidIntent();
    }
    function _risk(T.Report calldata r, bytes calldata s, uint8 action) private {
        if (msg.sender != relayer) revert Unauthorized();
        if (r.action != action) revert InvalidIntent();
        address owner = positionOwner[r.positionId];
        if (owner == address(0)) revert InvalidOwner();
        _apply(r, s, owner);
        emit CRiskExecuted(r.operationId, r.positionId, owner, rules.reportDigest(r), action);
    }

    function _apply(T.Report calldata r, bytes calldata s, address owner) private {
        E.Position memory before_ = rules.getPosition(r.positionId);
        _checkLock(r, owner, before_);
        // The same immutable arithmetic as rules determines settlement, never an external PnL input.
        M.Mark memory mark = M.mark(r.side, r.quantity, r.entryPriceE8, r.collateralAfter, r.priceE8);
        if (r.action == T.OPEN) rules.open(r, s);
        else if (r.action == T.TOP_UP) rules.topUp(r, s);
        else if (r.action == T.CLOSE) rules.close(r, s);
        else if (r.action == T.START_CALL) rules.startMarginCall(r, s);
        else if (r.action == T.CLEAR_CALL) rules.clearMarginCall(r, s);
        else if (r.action == T.LIQUIDATE) rules.liquidate(r, s);
        else revert InvalidIntent();
        _checkAfter(r, before_, mark);
        if (r.action == T.OPEN) vault.lock(r.operationId, r.positionId, owner, r.collateralAfter);
        else if (r.action == T.TOP_UP) vault.increaseLock(r.operationId, r.positionId,
            owner, r.collateralBefore, r.collateralAfter - r.collateralBefore);
        else if (r.action == T.CLOSE || r.action == T.LIQUIDATE) {
            vault.settle(r.operationId, r.positionId, owner, r.collateralAfter, mark.realized, mark.badDebt);
        }
    }
    function _checkLock(T.Report calldata r, address owner, E.Position memory p) private view {
        (address lockedOwner, uint256 amount, bool terminal) = vault.positionLocks(r.positionId);
        if (r.action == T.OPEN) {
            if (p.status != E.Status.NONE || lockedOwner != address(0) || amount != 0 || terminal)
                revert InvalidState();
        } else {
            if (lockedOwner != owner || terminal || amount != p.collateral
                || p.collateral != r.collateralBefore || p.accountRef != accountRef(owner)) revert InvalidState();
        }
    }
    function _checkAfter(T.Report calldata r, E.Position memory before_, M.Mark memory m) private view {
        E.Position memory p = rules.getPosition(r.positionId);
        if (p.accountRef != r.accountRef || p.side != r.side || p.leverage != r.leverage
            || p.quantity != r.quantity || p.entryPriceE8 != r.entryPriceE8 || p.collateral != r.collateralAfter
            || p.version != r.expectedVersion + 1 || p.lastObservedAt != r.observedAt
            || !rules.usedOperations(r.operationId)) revert InvalidState();
        E.Status status = before_.status;
        uint256 epoch = before_.callEpoch;
        uint256 deadline = before_.deadline;
        if (r.action == T.OPEN) { status = E.Status.ACTIVE; epoch = 0; deadline = 0; }
        else if (r.action == T.START_CALL) { status = E.Status.MARGIN_CALL; ++epoch; deadline = block.timestamp + 60; }
        else if (r.action == T.CLEAR_CALL || (r.action == T.TOP_UP && status == E.Status.MARGIN_CALL && !M.atRisk(m))) {
            status = E.Status.ACTIVE; deadline = 0;
        } else if (r.action == T.CLOSE) status = E.Status.CLOSED;
        else if (r.action == T.LIQUIDATE) status = E.Status.LIQUIDATED;
        if (p.status != status || p.callEpoch != epoch || p.deadline != deadline) revert InvalidState();
        bytes32 active = rules.activePosition(p.accountRef);
        bool terminal = status == E.Status.CLOSED || status == E.Status.LIQUIDATED;
        if (active != (terminal ? bytes32(0) : r.positionId)) revert InvalidState();
    }
}
