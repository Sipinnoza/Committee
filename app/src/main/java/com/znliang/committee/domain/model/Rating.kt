package com.znliang.committee.domain.model

/**
 * 6 级评级体系，与规格文档 §1.3 完全对应
 */
enum class Rating(val displayName: String, val level: Int) {
    BUY("Buy", 1),
    OVERWEIGHT("Overweight", 2),
    HOLD_PLUS("Hold+", 3),
    HOLD("Hold", 4),
    UNDERWEIGHT("Underweight", 5),
    SELL("Sell", 6);
}
