// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {ECDSA} from "@openzeppelin/contracts/utils/cryptography/ECDSA.sol";
import {EIP712} from "@openzeppelin/contracts/utils/cryptography/EIP712.sol";
import {FuturesMath} from "./FuturesMath.sol";
import {FuturesReportTypes as T} from "./FuturesReportTypes.sol";

/// @notice Validates DB-custodied mock futures; DOES NOT escrow, mint or transfer ERC-20 collateral.
/// @dev Executor's collateral assertions are trusted. User ownership and DB funding are not proven here.
contract FuturesEngine is EIP712 {
    uint256 public constant REPORT_TTL = 30;
    uint256 public constant GRACE_SECONDS = 60;
    address public immutable executor;
    address public immutable priceSigner;
    bytes32 public immutable supportedSymbolHash;
    bytes32 public immutable policyHash;

    enum Status { NONE, ACTIVE, MARGIN_CALL, CLOSED, LIQUIDATED }
    struct Position {
        bytes32 accountRef;
        uint8 side;
        uint8 leverage;
        uint256 quantity;
        uint256 entryPriceE8;
        uint256 collateral;
        Status status;
        uint256 version;
        uint256 callEpoch;
        uint256 deadline;
        uint256 lastObservedAt;
    }
    mapping(bytes32 => Position) private positions;
    mapping(bytes32 => bytes32) public activePosition;
    mapping(bytes32 => bool) public usedOperations;

    event OperationApplied(bytes32 indexed operationId, bytes32 indexed positionId, uint8 action,
        uint256 version, uint256 priceE8, uint256 collateral, int256 unrealized,
        int256 realized, uint256 badDebt, uint256 callEpoch, uint256 deadline);
    event FuturesReportConsumed(bytes32 indexed operationId, uint256 observedAt, uint256 validUntil);
    error Unauthorized();
    error InvalidReport();
    error StaleVersion();
    error InvalidState();
    error AlreadyUsed();
    error GraceNotOver();
    error RiskConditionNotMet();

    constructor(address executor_, address signer_, bytes32 symbol_, bytes32 policy_)
        EIP712("TokenizedMarketFutures", "1")
    {
        if (executor_ == address(0) || signer_ == address(0) || executor_ == signer_
            || symbol_ == bytes32(0) || policy_ == bytes32(0)) revert InvalidReport();
        executor = executor_; priceSigner = signer_; supportedSymbolHash = symbol_; policyHash = policy_;
    }

    function open(T.Report calldata r, bytes calldata s) external { _execute(r, s, T.OPEN); }
    function topUp(T.Report calldata r, bytes calldata s) external { _execute(r, s, T.TOP_UP); }
    function startMarginCall(T.Report calldata r, bytes calldata s) external { _execute(r, s, T.START_CALL); }
    function clearMarginCall(T.Report calldata r, bytes calldata s) external { _execute(r, s, T.CLEAR_CALL); }
    function close(T.Report calldata r, bytes calldata s) external { _execute(r, s, T.CLOSE); }
    function liquidate(T.Report calldata r, bytes calldata s) external { _execute(r, s, T.LIQUIDATE); }
    function getPosition(bytes32 id) external view returns (Position memory) { return positions[id]; }
    function reportDigest(T.Report calldata r) public view returns (bytes32) { return _hashTypedDataV4(T.hash(r)); }

    function _execute(T.Report calldata r, bytes calldata signature, uint8 action) private {
        _validate(r, signature, action);
        Position storage p = positions[r.positionId];
        if (action == T.OPEN) {
            if (p.status != Status.NONE || activePosition[r.accountRef] != bytes32(0)) revert InvalidState();
            if (r.expectedVersion != 0 || r.expectedCallEpoch != 0 || r.collateralBefore != 0
                || r.entryPriceE8 != r.priceE8 || r.quantity != FuturesMath.quantity(r.collateralAfter, r.leverage, r.priceE8))
                revert InvalidReport();
            p.accountRef = r.accountRef; p.side = r.side; p.leverage = r.leverage;
            p.quantity = r.quantity; p.entryPriceE8 = r.priceE8; p.collateral = r.collateralAfter;
            p.status = Status.ACTIVE; activePosition[r.accountRef] = r.positionId;
        } else {
            if (p.status != Status.ACTIVE && p.status != Status.MARGIN_CALL) revert InvalidState();
            if (p.version != r.expectedVersion || p.callEpoch != r.expectedCallEpoch) revert StaleVersion();
            if (p.accountRef != r.accountRef || p.side != r.side || p.leverage != r.leverage
                || p.quantity != r.quantity || p.entryPriceE8 != r.entryPriceE8 || p.collateral != r.collateralBefore
                || r.observedAt < p.lastObservedAt) revert InvalidReport();
            if (action == T.TOP_UP) {
                if (r.collateralAfter <= p.collateral) revert InvalidReport();
                FuturesMath.amount(r.collateralAfter); p.collateral = r.collateralAfter;
            } else if (r.collateralAfter != p.collateral) revert InvalidReport();
        }

        FuturesMath.Mark memory m = FuturesMath.mark(p.side, p.quantity, p.entryPriceE8, p.collateral, r.priceE8);
        bool risk = FuturesMath.atRisk(m);
        if (action == T.OPEN && risk) revert RiskConditionNotMet();
        if (action == T.START_CALL) {
            if (p.status != Status.ACTIVE || !risk) revert RiskConditionNotMet();
            p.status = Status.MARGIN_CALL; ++p.callEpoch; p.deadline = block.timestamp + GRACE_SECONDS;
        } else if (action == T.CLEAR_CALL) {
            if (p.status != Status.MARGIN_CALL || risk) revert RiskConditionNotMet();
            p.status = Status.ACTIVE; p.deadline = 0;
        } else if (action == T.TOP_UP && p.status == Status.MARGIN_CALL && !risk) {
            p.status = Status.ACTIVE; p.deadline = 0;
        } else if (action == T.LIQUIDATE) {
            if (p.status != Status.MARGIN_CALL) revert InvalidState();
            if (block.timestamp < p.deadline || r.observedAt < p.deadline || r.observedAt > block.timestamp)
                revert GraceNotOver();
            if (!risk) revert RiskConditionNotMet();
            p.status = Status.LIQUIDATED; activePosition[p.accountRef] = bytes32(0);
        } else if (action == T.CLOSE) {
            p.status = Status.CLOSED; activePosition[p.accountRef] = bytes32(0);
        }
        ++p.version; p.lastObservedAt = r.observedAt; usedOperations[r.operationId] = true;
        _emit(r, p, m);
    }

    function _validate(T.Report calldata r, bytes calldata s, uint8 action) private view {
        if (msg.sender != executor || r.executor != executor) revert Unauthorized();
        if (r.operationId == bytes32(0) || r.positionId == bytes32(0) || r.accountRef == bytes32(0)
            || r.symbolHash != supportedSymbolHash || r.policyHash != policyHash || r.action != action || r.side > 1
            || r.observedAt > type(uint256).max - REPORT_TTL || r.validUntil != r.observedAt + REPORT_TTL
            || r.observedAt > block.timestamp + 2 || block.timestamp > r.validUntil) revert InvalidReport();
        if (usedOperations[r.operationId]) revert AlreadyUsed();
        if (ECDSA.recover(reportDigest(r), s) != priceSigner) revert Unauthorized();
    }

    function _emit(T.Report calldata r, Position storage p, FuturesMath.Mark memory m) private {
        bool terminal = p.status == Status.CLOSED || p.status == Status.LIQUIDATED;
        emit OperationApplied(r.operationId, r.positionId, r.action, p.version, r.priceE8, p.collateral,
            m.unrealized, terminal ? m.realized : int256(0), terminal ? m.badDebt : 0, p.callEpoch, p.deadline);
        emit FuturesReportConsumed(r.operationId, r.observedAt, r.validUntil);
    }
}
