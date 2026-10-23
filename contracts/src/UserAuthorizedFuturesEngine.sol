// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {ECDSA} from "@openzeppelin/contracts/utils/cryptography/ECDSA.sol";
import {EIP712} from "@openzeppelin/contracts/utils/cryptography/EIP712.sol";
import {FuturesEngine} from "./FuturesEngine.sol";
import {FuturesReportTypes as T} from "./FuturesReportTypes.sol";
import {FuturesUserIntentTypes as U} from "./FuturesUserIntentTypes.sol";

/// @notice On-chain user consent for DB-custodied mock futures. NOT a collateral vault.
/// @dev The immutable rules engine's sole executor is THIS contract, not the relayer.
///      No unsigned user-action entry, admin override, upgrade, arbitrary call or key rotation.
contract UserAuthorizedFuturesEngine is EIP712 {
    address public immutable executor;
    FuturesEngine public immutable rules;
    mapping(bytes32 => address) public positionOwner;
    mapping(address => uint256) public nonces;
    bytes32 private constant ACCOUNT_DOMAIN = keccak256("TMS_FUTURES_WALLET_ACCOUNT_V1");

    struct Position {
        address owner;
        FuturesEngine.Position state;
    }

    event UserIntentConsumed(bytes32 indexed intentHash, address indexed owner,
        bytes32 indexed positionId, uint8 action, uint256 nonce);
    error UnauthorizedRelayer();
    error InvalidOwner();
    error InvalidAccount();
    error InvalidUserNonce();
    error InvalidUserDeadline();
    error InvalidUserSignature();
    error InvalidAction();

    constructor(address executor_, address priceSigner_, bytes32 symbol_, bytes32 policy_)
        EIP712("TokenizedMarketFuturesUserIntent", "1")
    {
        if (executor_ == address(0) || executor_ == priceSigner_) revert UnauthorizedRelayer();
        executor = executor_;
        // Composition preserves the tested financial rules WITHOUT inheriting unsigned entry points.
        rules = new FuturesEngine(address(this), priceSigner_, symbol_, policy_);
    }

    function accountRef(address owner) public view returns (bytes32) {
        return keccak256(abi.encode(ACCOUNT_DOMAIN, owner, rules.supportedSymbolHash()));
    }

    function getPosition(bytes32 id) external view returns (Position memory) {
        return Position(positionOwner[id], rules.getPosition(id));
    }

    function intentDigest(T.Report calldata report, U.Approval calldata approval) public view returns (bytes32) {
        return _hashTypedDataV4(U.hash(report, approval));
    }

    function open(T.Report calldata r, bytes calldata priceSignature, U.Approval calldata a,
        bytes calldata userSignature) external {
        _authorize(r, a, userSignature, T.OPEN);
        if (positionOwner[r.positionId] != address(0)) revert InvalidOwner();
        positionOwner[r.positionId] = a.owner;
        rules.open(r, priceSignature);
        _emit(r, a);
    }

    function topUp(T.Report calldata r, bytes calldata priceSignature, U.Approval calldata a,
        bytes calldata userSignature) external {
        _authorize(r, a, userSignature, T.TOP_UP);
        rules.topUp(r, priceSignature);
        _emit(r, a);
    }

    function close(T.Report calldata r, bytes calldata priceSignature, U.Approval calldata a,
        bytes calldata userSignature) external {
        _authorize(r, a, userSignature, T.CLOSE);
        rules.close(r, priceSignature);
        _emit(r, a);
    }

    // OPEN consent binds policyHash. Subsequent rule-driven risk actions need no fresh user signature.
    function startMarginCall(T.Report calldata r, bytes calldata s) external {
        _automatic(r); rules.startMarginCall(r, s);
    }
    function clearMarginCall(T.Report calldata r, bytes calldata s) external {
        _automatic(r); rules.clearMarginCall(r, s);
    }
    function liquidate(T.Report calldata r, bytes calldata s) external {
        _automatic(r); rules.liquidate(r, s);
    }

    function _authorize(T.Report calldata r, U.Approval calldata a, bytes calldata signature,
        uint8 action) private {
        _relayer();
        if (r.action != action) revert InvalidAction();
        // This version supports plain EOAs, not ERC-1271 or delegated-code wallet accounts.
        if (a.owner == address(0) || a.owner.code.length != 0
            || (action != T.OPEN && positionOwner[r.positionId] != a.owner))
            revert InvalidOwner();
        if (r.accountRef != accountRef(a.owner)) revert InvalidAccount();
        if (a.relayer != executor) revert UnauthorizedRelayer();
        if (a.nonce != nonces[a.owner]) revert InvalidUserNonce();
        if (block.timestamp > a.deadline || a.deadline > r.validUntil) revert InvalidUserDeadline();
        if (ECDSA.recover(intentDigest(r, a), signature) != a.owner) revert InvalidUserSignature();
        // Atomic with rules execution. A rejected report/transition reverts this reservation too.
        nonces[a.owner] = a.nonce + 1;
    }

    function _automatic(T.Report calldata r) private view {
        _relayer();
        if (positionOwner[r.positionId] == address(0)) revert InvalidOwner();
    }
    function _relayer() private view {
        if (msg.sender != executor) revert UnauthorizedRelayer();
    }
    function _emit(T.Report calldata r, U.Approval calldata a) private {
        emit UserIntentConsumed(intentDigest(r, a), a.owner, r.positionId, r.action, a.nonce);
    }
}
