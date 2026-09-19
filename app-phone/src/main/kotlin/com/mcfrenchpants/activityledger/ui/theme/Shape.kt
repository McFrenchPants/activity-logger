package com.mcfrenchpants.activityledger.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * D3 shapes: cards 16dp, fields and list containers 12dp, chips 8dp, buttons full pill.
 * Use these by name; [LedgerM3Shapes] maps them onto the M3 slots components read by default.
 */
object LedgerShapes {
    val card: Shape = RoundedCornerShape(16.dp)
    val field: Shape = RoundedCornerShape(12.dp)
    val listContainer: Shape = RoundedCornerShape(12.dp)
    val chip: Shape = RoundedCornerShape(8.dp)
    val button: Shape = CircleShape
}

/**
 * M3 slot mapping: chips read `small` (8dp), cards read `medium` (16dp), text fields read
 * `extraSmall` (12dp). M3 buttons are already full pill.
 */
internal val LedgerM3Shapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
