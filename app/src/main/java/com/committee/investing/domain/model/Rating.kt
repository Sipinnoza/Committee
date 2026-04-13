package com.committee.investing.domain.model

/**
 * 6 级评级体系，与规格文档 §1.3 完全对应
 */
enum class Rating(val displayName: String, val level: Int, val colorHex: String) {
    BUY("Buy", 1, "#00C853"),
    OVERWEIGHT("Overweight", 2, "#69F0AE"),
    HOLD_PLUS("Hold+", 3, "#FFD740"),
    HOLD("Hold", 4, "#FFD740"),
    UNDERWEIGHT("Underweight", 5, "#FF6D00"),
    SELL("Sell", 6, "#D50000");
}
