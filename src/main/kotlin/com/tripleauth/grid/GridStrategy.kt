package com.tripleauth.grid

import com.tripleauth.nexttrading.client.dto.CandleInterval
import com.tripleauth.nexttrading.client.dto.OrderSide
import com.tripleauth.nexttrading.client.dto.OrderType
import com.tripleauth.nexttrading.client.dto.TimeInForce
import com.tripleauth.nexttrading.strategy.Signal
import com.tripleauth.nexttrading.strategy.StrategyContext
import com.tripleauth.nexttrading.strategy.StrategySpec
import com.tripleauth.nexttrading.strategy.TradingStrategy
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.ZonedDateTime

/**
 * 목표가 스캘핑 전략 (Hana FeePlan 의 롱 재해석).
 *
 * 원본: Hana 의 Bybit FeePlan — 숏 지정가 진입(메이커 리베이트) → 아래에서 청산 →
 * 미체결 시 count 를 줄여 목표 완화 → 수익권이면 즉시 시장가 청산.
 * 모의투자 이관에서 바뀐 점:
 * - 리베이트/PostOnly/숏 없음 → 순수 시세차익의 매수-매도 사이클로 전환
 * - 상태는 서버(보유/미체결)를 기준으로 판단해 재시작에도 안전. count 만 메모리 유지
 *
 * 사이클:
 * 1. 무포지션 → 현재가 x (1 - buyDipRate) 지정가 매수. 가격이 chaseRate 이상 달아나면 취소 후 추격
 * 2. 보유 → 평균단가 x (1 + targetRate x count) 지정가 매도 (GTC)
 * 3. 매도 미체결로 decayMinutes 경과 → 취소, count-1 로 목표를 낮춰 재주문
 * 4. count 소진 후: 현재가가 평균단가 이상이면 시장가 청산 (원본의 "수익 나면 바로 시장가"),
 *    아니면 본전(평균단가) 지정가를 유지하며 회복을 기다린다
 */
@Component
class GridStrategy(
    private val properties: GridProperties,
) : TradingStrategy {

    private val logger = KotlinLogging.logger { }

    override val spec = StrategySpec(
        name = "grid",
        symbols = listOf(properties.symbol),
        candleInterval = CandleInterval.MIN_5,
        candleLimit = 2, // 이 전략은 캔들을 쓰지 않지만 컨텍스트 규격상 최소값을 둔다
        pollInterval = Duration.ofSeconds(properties.pollSeconds),
    )

    /** 남은 목표 감쇠 count. 포지션이 없어지면 초기화된다 */
    @Volatile
    internal var decayCount: Int = properties.maxDecayCount

    /** 현재 매도 주문을 걸어둔 시각 — decayMinutes 판정용 */
    @Volatile
    internal var sellPlacedAt: ZonedDateTime? = null

    override fun decide(context: StrategyContext): List<Signal> {
        val symbol = properties.symbol
        val price = context.quote(symbol)?.price ?: return emptyList()

        return if (context.hasPosition(symbol)) {
            decideSell(symbol, price, context)
        } else {
            decayCount = properties.maxDecayCount
            sellPlacedAt = null
            decideBuy(symbol, price, context)
        }
    }

    private fun decideBuy(symbol: String, price: BigDecimal, context: StrategyContext): List<Signal> {
        val desired = price.multiply(BigDecimal.ONE - properties.buyDipRate).setScale(2, RoundingMode.HALF_EVEN)

        val openBuy = context.openOrders(symbol).firstOrNull { it.side == OrderSide.BUY }
        if (openBuy != null) {
            val orderPrice = openBuy.limitPrice ?: return emptyList()
            // 가격이 달아나 주문이 뒤처졌으면 취소하고 다음 틱에 따라간다 (원본의 취소 후 재신청)
            val chaseLine = orderPrice.multiply(BigDecimal.ONE + properties.chaseRate)
            if (desired > chaseLine) {
                logger.info { "매수 추격 / 주문가=$orderPrice → 희망가=$desired 취소" }
                return listOf(Signal.Cancel(openBuy.orderId))
            }
            return emptyList()
        }

        val budget = context.buyingPower.multiply(properties.budgetRatio)
        val quantity = budget.divide(desired, 0, RoundingMode.DOWN)

        if (quantity < BigDecimal.ONE) {
            logger.warn { "예산 부족으로 매수 스킵 / budget=$budget price=$desired" }
            return emptyList()
        }

        logger.info { "지정가 매수 / $symbol qty=$quantity price=$desired (현재가=$price)" }

        return listOf(
            Signal.Buy(
                symbol = symbol,
                quantity = quantity,
                orderType = OrderType.LIMIT,
                limitPrice = desired,
                timeInForce = TimeInForce.DAY,
            ),
        )
    }

    private fun decideSell(symbol: String, price: BigDecimal, context: StrategyContext): List<Signal> {
        val holding = context.holding(symbol) ?: return emptyList()
        val entryPrice = holding.avgEntryPrice

        val openSell = context.openOrders(symbol).firstOrNull { it.side == OrderSide.SELL }

        if (openSell != null) {
            val placedAt = sellPlacedAt ?: context.now.also { sellPlacedAt = it }

            // 미체결이 decayMinutes 를 넘기면 목표를 한 단계 낮춘다
            if (decayCount > 0 && Duration.between(placedAt, context.now) >= Duration.ofMinutes(properties.decayMinutes)) {
                decayCount--
                sellPlacedAt = null
                logger.info { "목표 감쇠 / 매도 미체결 ${properties.decayMinutes}분 경과 → count=$decayCount 재주문 예정" }
                return listOf(Signal.Cancel(openSell.orderId))
            }
            return emptyList()
        }

        // count 소진 + 수익권이면 즉시 시장가 청산 (원본: "수익 날시 그냥 바로 시장가 판매")
        if (decayCount <= 0 && price >= entryPrice) {
            logger.info { "시장가 청산 / 현재가=$price >= 평균단가=$entryPrice" }
            return listOf(Signal.Sell(symbol = symbol, quantity = holding.quantity))
        }

        // 목표가 = 평균단가 x (1 + targetRate x count). count 소진 시 본전 매도 대기
        val multiplier = BigDecimal.ONE + properties.targetRate.multiply(BigDecimal(decayCount.coerceAtLeast(0)))
        val sellPrice = entryPrice.multiply(multiplier).setScale(2, RoundingMode.HALF_EVEN)

        sellPlacedAt = context.now

        logger.info { "지정가 매도 / $symbol qty=${holding.quantity} price=$sellPrice (평균단가=$entryPrice count=$decayCount)" }

        return listOf(
            Signal.Sell(
                symbol = symbol,
                quantity = holding.quantity,
                orderType = OrderType.LIMIT,
                limitPrice = sellPrice,
                timeInForce = TimeInForce.GTC,
            ),
        )
    }
}
