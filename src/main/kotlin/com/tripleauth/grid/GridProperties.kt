package com.tripleauth.grid

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

@ConfigurationProperties(prefix = "grid")
data class GridProperties(
    /** 감시 종목 */
    val symbol: String = "AAPL",
    /** 매수 지정가: 현재가 대비 이만큼 아래 (0.003 = -0.3%) */
    val buyDipRate: BigDecimal = BigDecimal("0.003"),
    /** 목표 수익 단위. 매도가 = 평균단가 x (1 + targetRate x 남은 count) */
    val targetRate: BigDecimal = BigDecimal("0.01"),
    /** 목표 감쇠 시작 count. 미체결이 지속될 때마다 1씩 줄어 목표가가 내려온다 */
    val maxDecayCount: Int = 3,
    /** 매도 미체결 상태로 이 시간(분)이 지나면 취소 후 목표를 한 단계 낮춰 재주문 */
    val decayMinutes: Long = 30,
    /** 가격이 매수 지정가보다 이만큼 위로 달아나면 주문을 취소하고 따라간다 (0.005 = 0.5%) */
    val chaseRate: BigDecimal = BigDecimal("0.005"),
    /** 주문 가능 현금 중 진입에 사용할 비율 */
    val budgetRatio: BigDecimal = BigDecimal("0.5"),
    /** 전략 호출 주기 (초) */
    val pollSeconds: Long = 30,
)
