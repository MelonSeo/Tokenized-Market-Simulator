"""Bind existing isolated fixture actors by authenticated reads, never by numeric coincidence.

No signing, funding, database mutation, Docker or network client. 'request' is an
injected authenticated adapter (or a clearly labelled Mock in offline tests).
"""
from decimal import Decimal, InvalidOperation, localcontext
import re
from ai_pilot_preflight import need


def amount(value):
    need(type(value) is str and len(value) <= 80 and bool(re.fullmatch(r"[0-9]+(?:\.[0-9]{1,18})?", value)), "V2_FIXTURE_AMOUNT")
    try:
        return Decimal(value)
    except InvalidOperation:
        raise ValueError("V2_FIXTURE_AMOUNT") from None


def data(reply, name):
    status, body = reply
    need(status == 200 and body.get("status") == "SUCCESS" and body.get("tool") == name
         and isinstance(body.get("data"), dict), "V2_FIXTURE_TOOL_UNAVAILABLE")
    return body["data"]


def bind_fixture(recipe, request):
    need(recipe.get("scope") == "NEW_ISOLATED_PILOT_V2_NOT_USER_DATA" and
         recipe.get("financiallyFrozen") is True and recipe.get("mode") in {"MOCK_CONTROLLED", "REAL_ISOLATED"}, "V2_FIXTURE_SCOPE")
    need(re.fullmatch(r"[0-9a-f]{64}", recipe.get("financialFingerprint", "")) is not None, "V2_FIXTURE_FINGERPRINT")
    bound = {"bindingVersion": 2, "scope": recipe["scope"], "mode": recipe["mode"], "financialFingerprint": recipe["financialFingerprint"], "actors": {}}
    def tool(actor, name, arguments):
        return data(request(actor, "POST", "/api/ai/tools/" + name, {"arguments": arguments}), name)
    for key in ("owner", "other", "admin"):
        actor = recipe["actors"][key]
        allowed = {"uid", "quoteId", "orderId", "positionId", "unverifiedPositionId", "pendingOrderId", "cWallet", "cPositionId"}
        need(set(actor) <= allowed and type(actor.get("uid")) is int and actor["uid"] > 0, "V2_FIXTURE_ACTOR_FIELDS")
        status, me = request(key, "GET", "/api/me", None)
        need(status == 200 and type(me.get("id")) is int and me["id"] == actor["uid"] and
             me.get("role") == ("ADMIN" if key == "admin" else "USER"), "V2_JWT_ACTOR_MISMATCH")
        result = {**actor, "uid": me["id"]}
        if key == "admin":
            bound["actors"][key] = result
            continue
        quote = tool(key, "getQuote", {"quoteId": actor["quoteId"]})
        need(quote.get("quoteId") == actor["quoteId"] and quote.get("side") == "BUY" and
             amount(quote.get("inputAmount")) == 75000 and amount(quote.get("fee")) == 75, "V2_F1_FIXTURE_NOT_75000_75")
        relation = quote.get("amountRelation", {})
        need(relation.get("verification") == "QUOTED_ARITHMETIC_ONLY_NOT_EXECUTION" and relation.get("inputIncludesFee") is True
             and amount(relation.get("quotedTotalDebit")) == 75000 and amount(relation.get("includedFee")) == 75
             and amount(relation.get("additionalFeeDebit")) == 0 and amount(relation.get("netPricingInput")) == 74925, "V2_F1_RELATION_INVALID")
        price, output = amount(quote.get("price")), amount(relation.get("calculatedOutput"))
        with localcontext() as context:
            context.prec = 180
            need(price > 0 and int(output * 10**18) == int(Decimal(74925) * 10**18 / price), "V2_F1_OUTPUT_INVALID")
        order = tool(key, "getOrder", {"orderId": actor["orderId"]})
        need(order.get("orderId") == actor["orderId"] and order.get("side") == "BUY" and
             amount(order.get("inputAmount")) == 75000, "V2_F1_ORDER_MISMATCH")
        wallet_status, wallet = request(key, "GET", "/api/c-futures/wallet", None)
        need(wallet_status == 200 and wallet.get("wallet", "").lower() == actor["cWallet"].lower()
             and re.fullmatch(r"0x[0-9a-fA-F]{40}", wallet.get("wallet", "")) is not None
             and re.fullmatch(r"[0-9a-f]{64}", wallet.get("proofHash", "")) is not None, "V2_C_PROOF_REQUIRED")
        account = tool(key, "getCAccount", {})
        position = tool(key, "getCPosition", {"positionId": actor["cPositionId"]})
        status, config = request(key, "GET", "/api/c-futures/config", None)
        need(status == 200 and config.get("sameProviderHeaderRechecked") is True and
             all(config.get("value", {}).get(k) == v for k, v in recipe["cIdentity"].items()), "V2_C_IDENTITY_MISMATCH")
        need(set(recipe["cIdentity"]) == {"deploymentId", "chainId", "genesisHash", "token", "controller", "vault"}, "V2_C_IDENTITY_FIELDS")
        for observation in (account, position):
            need(observation.get("assetModel") == "C_ONCHAIN_VAULT_NOT_DB_LEDGER" and
                 re.fullmatch(r"0x[0-9a-fA-F]{64}", observation.get("observation", {}).get("hash", "")) is not None,
                 "V2_C_PINNED_OBSERVATION_REQUIRED")
        need(account.get("personalClaimsEnumerated") is False, "V2_C_SCOPE_INVALID")
        need(account.get("deploymentId") == recipe["cIdentity"]["deploymentId"] and
             position.get("deploymentId") == recipe["cIdentity"]["deploymentId"], "V2_C_IDENTITY_MISMATCH")
        result["cProofHash"] = wallet["proofHash"]
        bound["actors"][key] = result
    actors = bound["actors"]
    need(len({v["uid"] for v in actors.values()}) == 3 and
         actors["owner"]["quoteId"] != actors["other"]["quoteId"] and
         actors["owner"]["cWallet"].lower() != actors["other"]["cWallet"].lower(), "V2_FIXTURE_OWNERS_COLLIDE")
    # Actual service denial, not a boolean copied from a fixture file.
    for key, foreign in (("owner", "other"), ("other", "owner")):
        status, _ = request(key, "POST", "/api/ai/tools/getCPosition", {"arguments": {"positionId": actors[foreign]["cPositionId"]}})
        need(status == 404, "V2_C_FOREIGN_NOT_BLOCKED")
    return bound
