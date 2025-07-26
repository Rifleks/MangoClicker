package rifleks.clicker

data class GameData(
    var clicks: Long = 0,
    var clickCooldown: Double = 0.5,
    var mangoClickLevel: Int = 1,
    var lastClickTime: Long = 0L,
    var cooldownLevel: Int = 0,
    var rebirthCount: Int = 0,
    var rebirthBonus: Double = 0.0
)