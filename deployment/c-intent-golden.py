"""Independent static EIP-712 vector. Cast's local Keccak only: no RPC/keys.

No product encoder, library TYPE_HASH, ABI generator or signing endpoint is used.
These public numeric fixtures are not real owners, approvals or funded accounts.
"""
import json
import subprocess


def keccak(value):
    return subprocess.check_output(["cast", "keccak", "0x" + value.hex()],
                                   text=True, timeout=10).strip()


def word(value):
    return value.to_bytes(32, "big")


if __name__ == "__main__":
    type_literal = (
        "CFuturesIntent(address owner,address relayer,bytes32 operationId,bytes32 positionId,"
        "bytes32 accountRef,bytes32 symbolHash,uint8 action,uint256 expectedVersion,"
        "uint256 expectedCallEpoch,uint8 side,uint8 leverage,uint256 entryPriceE8,"
        "uint256 collateralBefore,uint256 collateralAfter,uint256 priceMinE8,"
        "uint256 priceMaxE8,uint256 quantityMin,uint256 quantityMax,uint256 nonce,"
        "uint256 issuedAt,uint256 deadline,bytes32 policyHash)"
    )
    values = [int("11" * 20, 16), int("22" * 20, 16), 3, 4, 5, 6, 0, 0, 0,
              1, 10, 0, 0, 100 * 10**18, 99 * 10**8, 101 * 10**8,
              9 * 10**18, 11 * 10**18, 7, 1000, 1120, 8]
    type_hash = keccak(type_literal.encode())
    struct_hash = keccak(bytes.fromhex(type_hash[2:]) + b"".join(word(x) for x in values))
    domain_type = keccak(b"EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)")
    domain_hash = keccak(b"".join([
        bytes.fromhex(domain_type[2:]),
        bytes.fromhex(keccak(b"TokenizedMarketCFuturesUserIntent")[2:]),
        bytes.fromhex(keccak(b"1")[2:]), word(31337), word(int("33" * 20, 16))]))
    digest = keccak(b"\x19\x01" + bytes.fromhex(domain_hash[2:]) + bytes.fromhex(struct_hash[2:]))
    print(json.dumps({"typeHash": type_hash, "structHash": struct_hash,
                      "domainHash": domain_hash, "digest": digest,
                      "values": [str(x) for x in values], "chainId": 31337,
                      "verifyingContract": "0x" + "33" * 20, "rpcCalls": 0}, indent=2))
