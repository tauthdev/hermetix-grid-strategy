# next-grid-strategy

**목표가 스캘핑 전략** — [next-trading-core](https://github.com/tauthdev/next-trading-core) 기반 넥스트증권 모의투자 봇.

원본은 Hana 프로젝트의 Bybit FeePlan(메이커 리베이트 숏 스캘핑)입니다. 모의투자에는 리베이트/PostOnly/공매도가 없으므로, 핵심 사이클(지정가 진입 → 목표가 청산 → 미체결 시 목표 완화 → 수익권 즉시 시장가)을 **매수-매도 방향으로 뒤집어** 이식했습니다.

## 전략 사이클

1. **매수**: 무포지션이면 현재가 × (1 − `buy-dip-rate`) 지정가 매수. 가격이 `chase-rate` 이상 달아나면 취소 후 추격
2. **매도**: 보유하면 평균단가 × (1 + `target-rate` × count) 지정가 매도 (GTC, count 초기값 = `max-decay-count`)
3. **목표 감쇠**: 매도 미체결로 `decay-minutes` 경과 시 취소하고 count−1 로 목표를 낮춰 재주문
4. **count 소진 후**: 현재가 ≥ 평균단가면 **즉시 시장가 청산**, 아니면 본전 지정가로 회복 대기

상태는 서버(보유/미체결)를 기준으로 판단하므로 재시작에 안전합니다. count 만 메모리에 있어 재시작 시 `max-decay-count` 로 돌아갑니다.

## 실행

```bash
export NEXT_CLIENT_ID=pk_test_...
export NEXT_CLIENT_SECRET=sk_test_...
./gradlew bootRun
```

## 설정 (application.yml)

```yaml
grid:
  symbol: AAPL         # 감시 종목
  buy-dip-rate: 0.003  # 매수 지정가 (현재가 대비 -0.3%)
  target-rate: 0.01    # 목표 수익 단위 (+1% x count)
  max-decay-count: 3   # 목표 감쇠 시작 count
  decay-minutes: 30    # 감쇠 판정 시간
  chase-rate: 0.005    # 매수 추격 임계
  budget-ratio: 0.5    # 진입 예산 비율
  poll-seconds: 30     # 판정 주기
```

## 원본 대비 주의점

원본 FeePlan 의 수익원이던 메이커 리베이트가 없으므로, 이 전략의 손익은 순수하게 "딥 매수 → 반등 매도"의 시세차익입니다. 횡보/상승장에 유리하고 하락 추세에서는 본전 대기 상태가 길어질 수 있습니다.
