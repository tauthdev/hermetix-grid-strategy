package com.tripleauth.grid

import com.tripleauth.nexttrading.client.dto.AccountResponse
import com.tripleauth.nexttrading.client.dto.Holding
import com.tripleauth.nexttrading.client.dto.OrderResponse
import com.tripleauth.nexttrading.client.dto.OrderSide
import com.tripleauth.nexttrading.client.dto.OrderStatus
import com.tripleauth.nexttrading.client.dto.OrderType
import com.tripleauth.nexttrading.client.dto.Quote
import com.tripleauth.nexttrading.strategy.Signal
import com.tripleauth.nexttrading.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZonedDateTime

class GridStrategyTest {

    private val properties = GridProperties(
        symbols = listOf("AAPL"),
        buyDipRate = BigDecimal("0.003"),
        targetRate = BigDecimal("0.01"),
        maxDecayCount = 3,
        decayMinutes = 30,
        chaseRate = BigDecimal("0.005"),
        budgetRatio = BigDecimal("0.5"),
    )

    private lateinit var strategy: GridStrategy

    @BeforeEach
    fun setUp() {
        strategy = GridStrategy(properties)
    }

    private fun context(
        price: String = "100",
        holdingQty: String? = null,
        avgEntry: String = "100",
        openOrders: List<OrderResponse> = emptyList(),
        now: ZonedDateTime = ZonedDateTime.now(),
    ) = StrategyContext(
        now = now,
        quotes = mapOf("AAPL" to Quote("AAPL", BigDecimal(price), null, null, 0, null, null, Instant.now())),
        candles = emptyMap(),
        account = AccountResponse("acc_main", null, "USD", BigDecimal("10000"), BigDecimal("10000"), "ACTIVE"),
        holdings = holdingQty?.let { mapOf("AAPL" to Holding("AAPL", BigDecimal(it), BigDecimal(avgEntry), null, null, null, null)) } ?: emptyMap(),
        openOrders = openOrders,
        buyingPower = BigDecimal("10000"),
    )

    private fun order(side: OrderSide, limitPrice: String) = OrderResponse(
        orderId = "ord_1", status = OrderStatus.SUBMITTED, symbol = "AAPL",
        side = side, orderType = OrderType.LIMIT, limitPrice = BigDecimal(limitPrice),
    )

    @Test
    fun `무포지션이면 현재가 아래 지정가 매수를 건다`() {
        val signals = strategy.decide(context(price = "100"))

        assertThat(signals).hasSize(1)
        val buy = signals[0] as Signal.Buy
        assertThat(buy.orderType).isEqualTo(OrderType.LIMIT)
        assertThat(buy.limitPrice).isEqualByComparingTo(BigDecimal("99.70")) // 100 * 0.997
        assertThat(buy.quantity).isEqualByComparingTo(BigDecimal("50"))     // 10000*0.5/99.70
    }

    @Test
    fun `가격이 달아나면 매수 주문을 취소한다`() {
        val signals = strategy.decide(
            context(price = "102", openOrders = listOf(order(OrderSide.BUY, "99.70"))),
        )

        // 희망가 101.694 > 99.70*1.005=100.1985 → 추격 취소
        assertThat(signals).hasSize(1)
        assertThat(signals[0]).isInstanceOf(Signal.Cancel::class.java)
    }

    @Test
    fun `가격이 조금 움직였으면 매수 주문을 유지한다`() {
        val signals = strategy.decide(
            context(price = "100.2", openOrders = listOf(order(OrderSide.BUY, "99.70"))),
        )

        assertThat(signals).isEmpty()
    }

    @Test
    fun `보유하면 평균단가에 목표율 x count 를 더한 지정가 매도를 건다`() {
        val signals = strategy.decide(context(price = "100", holdingQty = "50", avgEntry = "100"))

        assertThat(signals).hasSize(1)
        val sell = signals[0] as Signal.Sell
        assertThat(sell.orderType).isEqualTo(OrderType.LIMIT)
        assertThat(sell.limitPrice).isEqualByComparingTo(BigDecimal("103.00")) // 100 * (1 + 0.01*3)
    }

    @Test
    fun `매도 미체결이 decayMinutes 를 넘기면 취소하고 count 를 줄인다`() {
        val now = ZonedDateTime.now()
        strategy.sellPlacedAt["AAPL"] = now.minusMinutes(31)

        val signals = strategy.decide(
            context(price = "100", holdingQty = "50", openOrders = listOf(order(OrderSide.SELL, "103.00")), now = now),
        )

        assertThat(signals).hasSize(1)
        assertThat(signals[0]).isInstanceOf(Signal.Cancel::class.java)
        assertThat(strategy.decayCount["AAPL"]).isEqualTo(2)

        // 다음 틱: 낮아진 목표로 재주문
        val next = strategy.decide(context(price = "100", holdingQty = "50", now = now))
        assertThat((next[0] as Signal.Sell).limitPrice).isEqualByComparingTo(BigDecimal("102.00"))
    }

    @Test
    fun `count 소진 후 수익권이면 시장가로 청산한다`() {
        strategy.decayCount["AAPL"] = 0

        val signals = strategy.decide(context(price = "100.5", holdingQty = "50", avgEntry = "100"))

        assertThat(signals).hasSize(1)
        val sell = signals[0] as Signal.Sell
        assertThat(sell.orderType).isEqualTo(OrderType.MARKET)
    }

    @Test
    fun `count 소진 후 손실권이면 본전 지정가로 대기한다`() {
        strategy.decayCount["AAPL"] = 0

        val signals = strategy.decide(context(price = "98", holdingQty = "50", avgEntry = "100"))

        assertThat(signals).hasSize(1)
        val sell = signals[0] as Signal.Sell
        assertThat(sell.orderType).isEqualTo(OrderType.LIMIT)
        assertThat(sell.limitPrice).isEqualByComparingTo(BigDecimal("100.00"))
    }

    @Test
    fun `포지션이 사라지면 count 가 초기화된다`() {
        strategy.decayCount["AAPL"] = 0

        strategy.decide(context(price = "100"))

        assertThat(strategy.decayCount["AAPL"]).isEqualTo(3)
    }
}
