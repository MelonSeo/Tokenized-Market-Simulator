"""Mock authenticated API contract, not actual JWT/PG/Anvil acceptance."""
import copy
import unittest
from test_ai_pilot_coordinator import fixture
from ai_pilot_fixture import bind_fixture


class MockFixtureApi:
    def __init__(self):
        self.recipe = fixture()
        self.recipe.update(financiallyFrozen=True, cIdentity={"deploymentId": "controlled-deployment", "chainId": "31337",
            "genesisHash": "0x" + "a" * 64, "token": "0x" + "b" * 40, "controller": "0x" + "c" * 40, "vault": "0x" + "d" * 40})
        self.mutation = lambda actor, path, result: result
        self.calls = []

    def request(self, actor, method, path, body):
        self.calls.append((actor, method, path, body))
        own = self.recipe["actors"][actor]
        if path == "/api/me":
            result = (200, {"id": own["uid"], "role": "ADMIN" if actor == "admin" else "USER"})
        elif path == "/api/c-futures/wallet":
            result = (200, {"wallet": own["cWallet"], "proofHash": "a" * 64})
        elif path == "/api/c-futures/config":
            result = (200, {"value": self.recipe["cIdentity"], "sameProviderHeaderRechecked": True})
        else:
            name = path.rsplit("/", 1)[-1]
            data = {"deploymentId": "controlled-deployment", "assetModel": "C_ONCHAIN_VAULT_NOT_DB_LEDGER", "observation": {"hash": "0x" + "e" * 64}}
            if name == "getQuote":
                data = {"quoteId": own["quoteId"], "side": "BUY", "inputAmount": "75000", "fee": "75", "price": "75000",
                        "amountRelation": {"verification": "QUOTED_ARITHMETIC_ONLY_NOT_EXECUTION", "inputIncludesFee": True,
                            "quotedTotalDebit": "75000", "includedFee": "75", "additionalFeeDebit": "0", "netPricingInput": "74925", "calculatedOutput": "0.999"}}
            elif name == "getOrder":
                data = {"orderId": own["orderId"], "side": "BUY", "inputAmount": "75000"}
            elif name == "getCAccount":
                data["personalClaimsEnumerated"] = False
            elif name == "getCPosition" and body["arguments"]["positionId"] != own["cPositionId"]:
                return 404, {"code": "RESOURCE_NOT_FOUND"}
            result = (200, {"tool": name, "status": "SUCCESS", "data": data})
        return self.mutation(actor, path, result)


class FixtureConnectionTests(unittest.TestCase):
    def test_two_real_contract_shapes_require_own_quote_wallet_and_c_position(self):
        api = MockFixtureApi()
        result = bind_fixture(api.recipe, api.request)
        self.assertEqual(2, result["bindingVersion"])
        self.assertEqual("MOCK_CONTROLLED", result["mode"])
        self.assertNotEqual(result["actors"]["owner"]["quoteId"], result["actors"]["other"]["quoteId"])
        self.assertEqual(2, sum(p == "/api/c-futures/wallet" for _, _, p, _ in api.calls))
        self.assertTrue(all(method in {"GET", "POST"} and (path.startswith("/api/ai/tools/") or method == "GET") for _, method, path, _ in api.calls))

    def test_old_thousand_input_fee_addition_or_inaccurate_output_cannot_bind(self):
        for field, value in [("inputAmount", "1000"), ("fee", "1")]:
            api = MockFixtureApi()
            def mutate(actor, path, result):
                if path.endswith("/getQuote"):
                    result[1]["data"][field] = value
                return result
            api.mutation = mutate
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "V2_F1_FIXTURE_NOT_75000_75"):
                bind_fixture(api.recipe, api.request)
        for field, value in [("additionalFeeDebit", "75"), ("calculatedOutput", "1"), ("inputIncludesFee", False)]:
            api = MockFixtureApi()
            def mutate(actor, path, result):
                if path.endswith("/getQuote"):
                    result[1]["data"]["amountRelation"][field] = value
                return result
            api.mutation = mutate
            with self.subTest(field=field), self.assertRaises(ValueError):
                bind_fixture(api.recipe, api.request)

    def test_wrong_jwt_wallet_proof_model_or_deployment_reject(self):
        def bad_uid(actor, path, result):
            if path == "/api/me": result[1]["id"] = 999
            return result
        def no_proof(actor, path, result):
            if path.endswith("/wallet"): result[1].pop("proofHash")
            return result
        def wrong_model(actor, path, result):
            if path.endswith("/getCAccount"): result[1]["data"]["assetModel"] = "LEGACY_DB"
            return result
        def wrong_deploy(actor, path, result):
            if path.endswith("/getCPosition"): result[1]["data"]["deploymentId"] = "different"
            return result
        for mutate in (bad_uid, no_proof, wrong_model, wrong_deploy):
            api = MockFixtureApi();api.mutation = mutate
            with self.subTest(mutation=mutate.__name__), self.assertRaises(ValueError):
                bind_fixture(api.recipe, api.request)

    def test_user_data_or_secrets_never_accepted_as_trial_recipe(self):
        for change in ({"scope": "USER_CHECKPOINT"}, {"financiallyFrozen": False}):
            api = MockFixtureApi();api.recipe.update(change)
            with self.assertRaises(ValueError): bind_fixture(api.recipe, api.request)
        api = MockFixtureApi();api.recipe["actors"]["owner"]["password"] = "not-a-real-secret"
        with self.assertRaisesRegex(ValueError, "V2_FIXTURE_ACTOR_FIELDS"):
            bind_fixture(api.recipe, api.request)


if __name__ == "__main__":
    unittest.main()
