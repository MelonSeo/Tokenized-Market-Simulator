// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {ECDSA} from "@openzeppelin/contracts/utils/cryptography/ECDSA.sol";
import {EIP712} from "@openzeppelin/contracts/utils/cryptography/EIP712.sol";
import {FuturesEngine} from "./FuturesEngine.sol";
import {FuturesMath as M} from "./FuturesMath.sol";
import {FuturesReportTypes as T} from "./FuturesReportTypes.sol";
import {FuturesUserIntentV2Types as U} from "./FuturesUserIntentV2Types.sol";

/// @notice B-v2 user consent over DB-custodied mock collateral, NOT an ERC-20 vault.
/// @dev Relayer chooses a server-attested execution price ONLY within the signed bounds.
///      Market connection/five-second pre-sign freshness remain backend responsibilities.
contract UserAuthorizedFuturesEngineV2 is EIP712 {
    uint256 public constant MAX_USER_TTL = 120;
    address public immutable executor;
    FuturesEngine public immutable rules;
    mapping(bytes32 => address) public positionOwner;
    mapping(address => uint256) public nonces;
    bytes32 private constant ACCOUNT_DOMAIN = keccak256("TMS_FUTURES_WALLET_ACCOUNT_V1");

    event UserIntentExecutedV2(bytes32 indexed intentHash, address indexed owner,
        bytes32 indexed positionId, bytes32 operationId, bytes32 reportDigest, uint8 action, uint256 nonce);
    event UserNonceCancelledV2(address indexed owner, uint256 nonce);
    error UnauthorizedRelayer();
    error InvalidOwner();
    error InvalidNonce();
    error InvalidUserWindow();
    error InvalidUserSignature();
    error IntentReportMismatch();
    error InvalidBounds();
    error ExecutionOutsideBounds();

    constructor(address executor_, address signer_, bytes32 symbol_, bytes32 policy_)
        EIP712("TokenizedMarketFuturesUserIntent", "2")
    {
        if (executor_ == address(0) || executor_ == signer_) revert UnauthorizedRelayer();
        executor = executor_;
        rules = new FuturesEngine(address(this), signer_, symbol_, policy_);
    }

    function accountRef(address owner) public view returns (bytes32) {
        return keccak256(abi.encode(ACCOUNT_DOMAIN, owner, rules.supportedSymbolHash()));
    }

    function intentDigest(U.Intent calldata intent) public view returns (bytes32) {
        return _hashTypedDataV4(U.hash(intent));
    }

    /// @notice Caller-owned nonce only. A submitted cancellation is not final until canonical inclusion.
    /// @dev Cannot cancel rule-driven risk actions, alter a position, or release DB collateral.
    function cancelNonce(uint256 expectedNonce) external {
        if (msg.sender.code.length != 0) revert InvalidOwner();
        if (nonces[msg.sender] != expectedNonce) revert InvalidNonce();
        nonces[msg.sender] = expectedNonce + 1;
        emit UserNonceCancelledV2(msg.sender, expectedNonce);
    }

    function open(T.Report calldata r, bytes calldata priceSignature, U.Intent calldata i,
        bytes calldata userSignature) external {
        _authorize(r, i, userSignature, T.OPEN);
        if (positionOwner[r.positionId] != address(0)) revert InvalidOwner();
        positionOwner[r.positionId] = i.owner;
        rules.open(r, priceSignature);
        _emit(r, i);
    }

    function topUp(T.Report calldata r, bytes calldata priceSignature, U.Intent calldata i,
        bytes calldata userSignature) external {
        _authorize(r, i, userSignature, T.TOP_UP);
        rules.topUp(r, priceSignature);
        _emit(r, i);
    }

    function close(T.Report calldata r, bytes calldata priceSignature, U.Intent calldata i,
        bytes calldata userSignature) external {
        _authorize(r, i, userSignature, T.CLOSE);
        rules.close(r, priceSignature);
        _emit(r, i);
    }

    function startMarginCall(T.Report calldata r, bytes calldata s) external {
        _automatic(r); rules.startMarginCall(r, s);
    }
    function clearMarginCall(T.Report calldata r, bytes calldata s) external {
        _automatic(r); rules.clearMarginCall(r, s);
    }
    function liquidate(T.Report calldata r, bytes calldata s) external {
        _automatic(r); rules.liquidate(r, s);
    }

    function _authorize(T.Report calldata r, U.Intent calldata i, bytes calldata signature, uint8 action) private {
        _relayer();
        if (i.owner == address(0) || i.owner.code.length != 0
            || (action != T.OPEN && positionOwner[r.positionId] != i.owner)) revert InvalidOwner();
        if (i.relayer != executor) revert UnauthorizedRelayer();
        if (i.nonce != nonces[i.owner]) revert InvalidNonce();
        if (i.issuedAt > block.timestamp || i.deadline <= i.issuedAt
            || i.deadline - i.issuedAt > MAX_USER_TTL || block.timestamp > i.deadline) revert InvalidUserWindow();
        if (ECDSA.recover(intentDigest(i), signature) != i.owner) revert InvalidUserSignature();
        if (i.operationId != r.operationId || i.positionId != r.positionId || i.action != action || r.action != action
            || i.accountRef != accountRef(i.owner) || i.accountRef != r.accountRef || i.symbolHash != r.symbolHash
            || i.policyHash != r.policyHash || r.executor != address(this)
            || i.expectedVersion != r.expectedVersion || i.expectedCallEpoch != r.expectedCallEpoch
            || i.side != r.side || i.leverage != r.leverage
            || i.collateralBefore != r.collateralBefore || i.collateralAfter != r.collateralAfter) revert IntentReportMismatch();
        // Signed explicit bounds, never implicit max uint / unbounded defaults.
        if (i.priceMinE8 == 0 || i.priceMinE8 > i.priceMaxE8 || i.priceMaxE8 >= 1e20
            || i.quantityMin == 0 || i.quantityMin > i.quantityMax || i.quantityMax >= 1e30) revert InvalidBounds();
        if (r.priceE8 < i.priceMinE8 || r.priceE8 > i.priceMaxE8
            || r.quantity < i.quantityMin || r.quantity > i.quantityMax) revert ExecutionOutsideBounds();
        if (action == T.OPEN) {
            if (i.entryPriceE8 != 0 || r.entryPriceE8 != r.priceE8
                || r.quantity != M.quantity(i.collateralAfter, i.leverage, r.priceE8)) revert IntentReportMismatch();
        } else {
            if (i.quantityMin != i.quantityMax || i.entryPriceE8 != r.entryPriceE8) revert IntentReportMismatch();
        }
        // Inner rules enforce existing position, exact state, price signature/domain/TTL and all math.
        // Reverts in either contract roll back this nonce and OPEN owner assignment atomically.
        nonces[i.owner] = i.nonce + 1;
    }

    function _automatic(T.Report calldata r) private view {
        _relayer();
        if (positionOwner[r.positionId] == address(0)) revert InvalidOwner();
    }
    function _relayer() private view { if (msg.sender != executor) revert UnauthorizedRelayer(); }
    function _emit(T.Report calldata r, U.Intent calldata i) private {
        emit UserIntentExecutedV2(intentDigest(i), i.owner, r.positionId, r.operationId,
            rules.reportDigest(r), r.action, i.nonce);
    }
}
