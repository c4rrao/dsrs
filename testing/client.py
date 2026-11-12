"""HTTP clients for the DSRS stacks. Participant tokens are signed directly with the dev JWT
secret instead of going through registration."""

import jwt
import time
import uuid
import requests
from typing import Optional, Any
from dataclasses import dataclass

from config import PARTICIPANT_STACKS, OPERATOR_STACK, AUDITOR_STACK


@dataclass
class ApiResponse:
    success: bool
    status_code: int
    data: Any
    error: Optional[str] = None


class ParticipantClient:
    def __init__(self, participant_index: int, party: str):
        config = PARTICIPANT_STACKS.get(participant_index)
        if config is None:
            raise ValueError(
                f"No participant stack {participant_index}; only stack 1 is deployed"
            )
        self.base_url = config["base_url"]
        self.jwt_secret = config["jwt_secret"]
        self.party = party
        self.token = self._generate_token()

    def _generate_token(self) -> str:
        payload = {
            "sub": f"test-user-{self.party.lower()}",
            "party": self.party,
            "iss": "dsrs",          # must match JwtConfig.issuer
            "aud": "dsrs-users",    # must match JwtConfig.audience
            "iat": int(time.time()),
            "exp": int(time.time()) + 3600,
        }
        return jwt.encode(payload, self.jwt_secret, algorithm="HS256")

    def _headers(self) -> dict:
        return {
            "Authorization": f"Bearer {self.token}",
            "Content-Type": "application/json",
        }

    def _request(self, method: str, path: str, data: Optional[dict] = None) -> ApiResponse:
        url = f"{self.base_url}{path}"
        try:
            if method == "GET":
                resp = requests.get(url, headers=self._headers(), timeout=10)
            elif method == "POST":
                resp = requests.post(url, headers=self._headers(), json=data, timeout=10)
            elif method == "DELETE":
                resp = requests.delete(url, headers=self._headers(), timeout=10)
            else:
                return ApiResponse(False, 0, None, f"Unknown method: {method}")

            try:
                resp_data = resp.json()
            except:
                resp_data = resp.text

            if resp.status_code >= 400:
                error = resp_data.get("error", str(resp_data)) if isinstance(resp_data, dict) else str(resp_data)
                return ApiResponse(False, resp.status_code, resp_data, error)

            return ApiResponse(True, resp.status_code, resp_data)
        except requests.RequestException as e:
            return ApiResponse(False, 0, None, str(e))


    def health(self) -> ApiResponse:
        try:
            resp = requests.get(f"{self.base_url}/health", timeout=5)
            return ApiResponse(resp.status_code == 200, resp.status_code, resp.json())
        except Exception as e:
            return ApiResponse(False, 0, None, str(e))


    def create_contribution_request(
        self,
        transaction_id: str,
        contribution_data: dict,
        role_in_transaction: str = "Buyer"
    ) -> ApiResponse:
        return self._request("POST", "/api/contributions/requests", {
            "transactionId": transaction_id,
            "contributionData": contribution_data,
            "roleInTransaction": role_in_transaction,
        })

    def list_contribution_requests(self) -> ApiResponse:
        return self._request("GET", "/api/contributions/requests")

    def withdraw_contribution_request(self, contract_id: str) -> ApiResponse:
        return self._request("DELETE", f"/api/contributions/requests/{contract_id}")

    def list_contributions(self) -> ApiResponse:
        return self._request("GET", "/api/contributions")


    def create_query_request(self, params: dict) -> ApiResponse:
        return self._request("POST", "/api/queries/requests", {"params": params})

    def list_query_requests(self) -> ApiResponse:
        return self._request("GET", "/api/queries/requests")

    def withdraw_query_request(self, contract_id: str) -> ApiResponse:
        return self._request("DELETE", f"/api/queries/requests/{contract_id}")

    def list_query_proposals(self) -> ApiResponse:
        return self._request("GET", "/api/queries/proposals")

    def accept_query_proposal(self, contract_id: str, holding_contract_id: str) -> ApiResponse:
        return self._request("POST", f"/api/queries/proposals/{contract_id}/accept", {
            "holdingContractId": holding_contract_id,
        })

    def list_funded_queries(self) -> ApiResponse:
        return self._request("GET", "/api/queries/funded")

    def reclaim_query_funds(self, contract_id: str) -> ApiResponse:
        """Only possible once the lock's deadline has passed (24h by default); the ledger enforces it."""
        return self._request("POST", f"/api/queries/funded/{contract_id}/reclaim")

    def reject_query_proposal(self, contract_id: str) -> ApiResponse:
        return self._request("POST", f"/api/queries/proposals/{contract_id}/reject")

    def list_query_results(self) -> ApiResponse:
        return self._request("GET", "/api/queries/results")


    def list_holdings(self) -> ApiResponse:
        return self._request("GET", "/api/holdings")

    def list_holding_offers(self) -> ApiResponse:
        return self._request("GET", "/api/holdings/offers")

    def accept_holding_offer(self, contract_id: str) -> ApiResponse:
        return self._request("POST", f"/api/holdings/offers/{contract_id}/accept")

    def reject_holding_offer(self, contract_id: str) -> ApiResponse:
        return self._request("POST", f"/api/holdings/offers/{contract_id}/reject")

    def transfer_holding(self, holding_contract_id: str, recipient: str, amount: Optional[int] = None,
                         reference: Optional[str] = None) -> ApiResponse:
        body = {"holdingContractId": holding_contract_id, "recipient": recipient}
        if amount is not None:
            body["amount"] = amount
        if reference:
            body["reference"] = reference
        return self._request("POST", "/api/holdings/transfer", body)

    def reward_groups(self, transaction_id: Optional[str] = None) -> ApiResponse:
        """Read from history, since a group is archived as it pays."""
        path = "/api/rewards/groups"
        if transaction_id:
            path += f"?transactionId={transaction_id}"
        return self._request("GET", path)

    def merge_holdings(self, holding_contract_id: str, other_contract_ids: list) -> ApiResponse:
        return self._request("POST", "/api/holdings/merge", {
            "holdingContractId": holding_contract_id,
            "otherContractIds": other_contract_ids,
        })


    def create_audit_request(self, request_type: str, params: dict) -> ApiResponse:
        """params: optional queryId, proposalId, rewardGroupId, transactionId, fromTime, toTime."""
        return self._request("POST", "/api/audit/requests", {
            "requestType": request_type,
            "params": params,
        })

    def list_audit_requests(self) -> ApiResponse:
        return self._request("GET", "/api/audit/requests")

    def cancel_audit_request(self, request_id: str) -> ApiResponse:
        return self._request("DELETE", f"/api/audit/requests/{request_id}")

    def list_audit_proposals(self) -> ApiResponse:
        return self._request("GET", "/api/audit/proposals")

    def fund_audit_proposal(self, proposal_id: str, holding_contract_id: str) -> ApiResponse:
        return self._request("POST", f"/api/audit/proposals/{proposal_id}/fund", {
            "holdingContractId": holding_contract_id,
        })

    def reject_audit_proposal(self, proposal_id: str) -> ApiResponse:
        return self._request("DELETE", f"/api/audit/proposals/{proposal_id}")

    def list_audit_results(self) -> ApiResponse:
        return self._request("GET", "/api/audit/results")


    def list_disputes(self) -> ApiResponse:
        return self._request("GET", "/api/disputes")

    def get_dispute_detail(self, contract_id: str) -> ApiResponse:
        return self._request("GET", f"/api/disputes/{contract_id}")

    def vote_on_dispute(self, contract_id: str, vote_for_index: int) -> ApiResponse:
        return self._request("POST", f"/api/disputes/{contract_id}/vote", {
            "voteForDataIndex": vote_for_index,
        })

    def propose_dispute_data(self, contract_id: str, contribution_data: dict) -> ApiResponse:
        """A proposal that matches an existing option is treated by the ledger as a vote for it."""
        return self._request("POST", f"/api/disputes/{contract_id}/propose-data", {
            "contributionData": contribution_data,
        })


class OperatorClient:
    def __init__(self):
        self.base_url = OPERATOR_STACK["base_url"]
        self.admin_key = OPERATOR_STACK["admin_api_key"]

    def _headers(self) -> dict:
        return {
            "Authorization": f"Bearer {self.admin_key}",
            "Content-Type": "application/json",
        }

    def _request(self, method: str, path: str, data: Optional[dict] = None) -> ApiResponse:
        url = f"{self.base_url}{path}"
        try:
            if method == "GET":
                resp = requests.get(url, headers=self._headers(), timeout=10)
            elif method == "POST":
                resp = requests.post(url, headers=self._headers(), json=data, timeout=10)
            elif method == "PUT":
                resp = requests.put(url, headers=self._headers(), json=data, timeout=10)
            else:
                return ApiResponse(False, 0, None, f"Unknown method: {method}")

            try:
                resp_data = resp.json()
            except:
                resp_data = resp.text

            if resp.status_code >= 400:
                error = resp_data.get("error", str(resp_data)) if isinstance(resp_data, dict) else str(resp_data)
                return ApiResponse(False, resp.status_code, resp_data, error)

            return ApiResponse(True, resp.status_code, resp_data)
        except requests.RequestException as e:
            return ApiResponse(False, 0, None, str(e))

    def health(self) -> ApiResponse:
        try:
            resp = requests.get(f"{self.base_url}/health", timeout=5)
            return ApiResponse(resp.status_code == 200, resp.status_code, resp.json())
        except Exception as e:
            return ApiResponse(False, 0, None, str(e))

    def get_system_state(self) -> ApiResponse:
        return self._request("GET", "/api/system/state")

    def get_processing_log(self, limit: int = 50) -> ApiResponse:
        return self._request("GET", f"/api/system/log?limit={limit}")

    def get_processing_log_for_contract(self, contract_id: str) -> ApiResponse:
        return self._request("GET", f"/api/system/log/{contract_id}")

    def list_contribution_requests(self) -> ApiResponse:
        return self._request("GET", "/api/contributions/requests")

    def get_contribution_by_transaction(self, transaction_id: str) -> ApiResponse:
        """404 until the operator has processed the request, which happens asynchronously."""
        return self._request("GET", f"/api/contributions/transaction/{transaction_id}")

    def list_contributions(self, limit: int = 100) -> ApiResponse:
        return self._request("GET", f"/api/contributions?limit={limit}")

    def list_query_requests(self) -> ApiResponse:
        return self._request("GET", "/api/queries/requests")

    def list_funded_queries(self) -> ApiResponse:
        return self._request("GET", "/api/queries/funded")

    def trigger_resync(self) -> ApiResponse:
        return self._request("POST", "/api/system/resync")

    def list_disputes(self, limit: int = 100) -> ApiResponse:
        return self._request("GET", f"/api/disputes?limit={limit}")

    def list_pending_disputes(self, limit: int = 100) -> ApiResponse:
        return self._request("GET", f"/api/disputes/pending?limit={limit}")

    def get_dispute(self, contract_id: str) -> ApiResponse:
        return self._request("GET", f"/api/disputes/{contract_id}")

    def mint_tokens(self, recipients: list) -> ApiResponse:
        """recipients: [{"party": "Alice::123...", "amount": 1000000}, ...]"""
        return self._request("POST", "/api/admin/mint", {
            "recipients": recipients,
        })

    def topup_tokens(self, consumer: str, amount: int, payment_reference: str) -> ApiResponse:
        return self._request("POST", "/api/mint/topup", {
            "consumer": consumer,
            "amount": amount,
            "paymentReference": payment_reference,
        })


    def get_pricing_config(self) -> ApiResponse:
        return self._request("GET", "/api/pricing/config")

    def update_pricing_config(
        self,
        base_price: int,
        level1_multiplier: str,
        level2_multiplier: str,
        coverage_weight: str = "2.0",
        coverage_saturation: str = "5.0",
        narrowness_weight: str = "0.5",
        trust_weight: str = "4.0",
        unit: str = "TOKENS",
    ) -> ApiResponse:
        return self._request("PUT", "/api/pricing/config", {
            "basePrice": base_price,
            "level1Multiplier": level1_multiplier,
            "level2Multiplier": level2_multiplier,
            "coverageWeight": coverage_weight,
            "coverageSaturation": coverage_saturation,
            "narrownessWeight": narrowness_weight,
            "trustWeight": trust_weight,
            "unit": unit,
        })

    def simulate_price(self, params: dict) -> ApiResponse:
        return self._request("POST", "/api/pricing/simulate", params)


class AuditorClient:
    """The auditor's own verification endpoints, separate from the on-ledger audit workflow."""

    def __init__(self):
        self.base_url = AUDITOR_STACK["base_url"]
        self.admin_key = AUDITOR_STACK["admin_api_key"]

    def _headers(self) -> dict:
        return {
            "Authorization": f"Bearer {self.admin_key}",
            "Content-Type": "application/json",
        }

    def _request(self, method: str, path: str, data: Optional[dict] = None) -> ApiResponse:
        url = f"{self.base_url}{path}"
        try:
            if method == "GET":
                resp = requests.get(url, headers=self._headers(), timeout=10)
            elif method == "POST":
                resp = requests.post(url, headers=self._headers(), json=data, timeout=10)
            else:
                return ApiResponse(False, 0, None, f"Unknown method: {method}")

            try:
                resp_data = resp.json()
            except:
                resp_data = resp.text

            if resp.status_code >= 400:
                error = resp_data.get("error", str(resp_data)) if isinstance(resp_data, dict) else str(resp_data)
                return ApiResponse(False, resp.status_code, resp_data, error)

            return ApiResponse(True, resp.status_code, resp_data)
        except requests.RequestException as e:
            return ApiResponse(False, 0, None, str(e))

    def health(self) -> ApiResponse:
        try:
            resp = requests.get(f"{self.base_url}/health", timeout=5)
            return ApiResponse(resp.status_code == 200, resp.status_code, resp.json())
        except Exception as e:
            return ApiResponse(False, 0, None, str(e))

    def health_detailed(self) -> ApiResponse:
        return self._request("GET", "/health/detailed")

    def verify_pricing(self, query_id: str = None, proposal_id: str = None) -> ApiResponse:
        return self._request("POST", "/api/audit/pricing", {
            "queryId": query_id,
            "proposalId": proposal_id,
        })

    def verify_reward(self, query_id: str, transaction_id: str, requester: str) -> ApiResponse:
        """Only the requester's own share is returned; non-contributors are refused."""
        return self._request("POST", "/api/audit/reward", {
            "queryId": query_id,
            "transactionId": transaction_id,
            "requester": requester,
        })

    def observed_mints(self) -> ApiResponse:
        return self._request("GET", "/api/audit/mints")

    def verify_inclusion(self, transaction_id: str, from_time: str, to_time: str) -> ApiResponse:
        return self._request("POST", "/api/audit/inclusion", {
            "transactionId": transaction_id,
            "fromTime": from_time,
            "toTime": to_time,
        })
