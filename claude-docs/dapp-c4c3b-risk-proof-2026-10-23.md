# C4c-3b — 지정 keeper 위험 action full proof

작성 2026-10-23. 구현 전 실제 C controller의 `_risk/_apply/_checkAfter`, Vault의 settle/FIFO, 기존 raw/envelope/auth/rules 및 두 reducer를 다시 대조했다. C4b PG 실행과 독립적인 순수 Java 단위다.

## 작은 구현 범위

기존 사용자 OPEN/TOP_UP/CLOSE wrapper에 Risk call 분기와 private 검증 경로만 추가한다. 기존 공통 envelope·배포·정확한 transaction prestate point·두 projection cursor·token Vault 잔고=Vault actualAssets 검사는 재사용한다. 사용자 경로와 기존37개 테스트는 유지하며 변경된 wrapper 전체를 의존 회귀 및 최초 독립 검토한다. 새 이름으로 과거 검토 상한을 우회하지 않는다.

START_CALL/CLEAR_CALL/LIQUIDATE는 raw sender=fixed relayer, Report Oracle EIP-712(domain chain/rules/executor), 명시적 owner/full position/mapping keys/used operation/active pointer를 검증한다. 사용자 nonce 및 ownerCode는 계약 `_risk`가 검사하지 않으므로 필수 조건이나 소비 대상으로 추가하지 않는다. 취소된 사용자 nonce도 자동청산을 막지 않는다.

기존 rules verifier가 Report30초/future2·상태·금융식·60초 유예·full OperationApplied와 ReportConsumed를 검사한다. CRiskExecuted의 모든 필드 및 exact event 순서를 연결한 뒤, Vault/FIFO/settlement/paidProfit/badDebt 회계는 기존 reducer에 위임한다. START/CLEAR에는 Vault/token event가 없고, LIQ는 필요한 settlement/claim event만 허용한다. terminal rules의 역사 collateral과 Vault lock0 차이를 보존한다. 두 reducer가 모두 성공한 뒤만 새 상태를 반환한다.

status0는 no-log 금융 불변 관측만 기록하고 caller/서명 허가나 실패 원인을 추정하지 않는다. 미지원 cash/process/cancel/pause/token 직접 call은 그대로 UNSUPPORTED다.

## 검증 계획과 명시적 한계

약30개 집중 JUnit으로 Long/Short START·CLEAR·LIQ, 정확한59/60초와 observedAt>=deadline<=now, 위험 지속/회복·TOP_UP 후 상태, Oracle/user/relayer 독립 시험키17/23/29, 잘못된 caller/domain/서명/전체 event 필드, nonce취소·ownerCode 비의존, fullprestate/배포/cursor/asset/extra-token/rollback 경계를 확인한다. 신규 risk 회귀와 기존 사용자37개를 함께 실행한다.

시간은 offline fixture timestamp일 뿐 실제 벽시계60초·Anvil·RPC·canonical/code 진실·시장5초/provider·PG 저장·전체 감사 coverage는 NOT_RUN이다. 결과는 PREVALIDATED_CONTEXT_ONLY, completeCoverage=false이며 MATCH를 반환하지 않는다. 기존 계약·금융 정책·서비스·평가 자료는 변경하지 않는다.

## 구현 상태 및 회계 projection 경계

### 실제 실행 / 최초 독립 검토 완료

별도 `review_c_boundaries`가 실제 C controller/Vault, 공통 wrapper 변경·모델, 신규32개 및 기존 사용자37개 테스트, runner/문서와 실행 manifest의 현재 SHA를 대조하여 **필수 수정 지적 없음**으로 판정했다. 기존 사용자 테스트가 3a 실행과 동일함도 확인했다. 검토자의 테스트 재실행은 없었다. 아래 “검토한다/NOT_RUN”은 검토 요청 또는 구현 직후 이력이다. 실제 canonical/RPC/prestate·code 진실성 및 벽시계60초는 여전히 검증하지 않았다.

총괄이 risk32PASS/0FAIL/0SKIP/0ABORTED를 실제 실행했다: `c-proof/20261023T034457Z-e92934f1`, sourceHash `0fae56e4d77b07131b9d13d34d9d4334bdfd4319ca662a68e2f01995689266fb`. 변경된 공통 wrapper의 사용자37PASS 회귀: `20261023T034517Z-8cb86a45`, sourceHash `9dae78df480b1a64ef0be1d7d809c127b8bef42e61a8c023fa2636d1248c6c49`. 둘 다 compile/test0·stderr 없음·소스 불변이며 Python runner 경계5PASS다. DB/RPC/서비스/유료 호출은 없었다. 이 실행 결과와 새 risk mode/문서·공통 wrapper 변경 전체를 최초 독립 검토한다. 아래 NOT_RUN은 구현 직후 이력이다.

기존 `CTransactionProofModels`의 scope 문자열과 `CTransactionProofVerifier`에 keeper 분기를 추가하고 새 `CTransactionRiskProofVerifierTest`를 작성했다. 기존 사용자37개는 수정하지 않았다. **신규 risk JUnit32개 작성**, compile·실행·독립 검토는 아직 NOT_RUN이다. 기존3a 검토 결과를 변경된 공통 wrapper의 검토로 자동 승계하지 않는다.

`Context.authorizationPrestate`는 keeper 경로에서도 position owner/full rules/mapping point를 담는 기존 운반 record로 재사용한다. 그 안의 사용자 nonce/key와 별도 ownerCode는 keeper 승인 검증에 사용하지 않으며, 결과 authorization=null은 사용자 Intent를 검증하지 않았다는 뜻이다. Oracle 서명은 keeper 경로에서 직접 검증한다.

실제 START/CLEAR는 rules.usedOperations만 소비하고 Vault.usedOperations에는 기록하지 않는다. C4a `State.usedOperations`는 **projection용 rules operation union**으로 이 action도 관측하므로 실제 Vault storage getter와 같다고 해석하면 안 된다. 이 단위는 해당 union과 rules operationUsed assertion의 일치만 검사한다. lock/settle Vault 호출이 없는 START/CLEAR에 Vault 금융 event를 만들어 넣지 않는다.

Short 시험은 명시적으로 prevalidated full rules의 side=SHORT를 제공한다. 그 이전 실제 Short OPEN을 이 단위에서 체인으로 재실행한 증거가 아니다. 성공 LIQ의 손익은 기존 rules 위험 조건상 손실이므로 신규 수익 Claim 생성 사례를 꾸미지 않는다. 기존 FIFO claim이 있어도 청산 손실은 house에만 보충되고 claim은 자동 지급되지 않는 회귀로 해당 경계를 검증한다.
