package com.whatsthesauce.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.whatsthesauce.R

/** UI 시안 팔레트 (diner delights) */
object Sauce {
    val Bg = Color(0xFFFBF6E6)        // creamy white 를 옅게 푼 바탕
    val Ink = Color(0xFF1B1B1E)       // soft black
    val Cherry = Color(0xFFD72638)    // cherry red
    val Cream = Color(0xFFF8E9A1)     // creamy white
    val Teal = Color(0xFF3B9C9C)      // turquoise
    val Sky = Color(0xFF82A6B1)       // faded blue sky
    val Muted = Color(0xFF4F6B75)     // sky 를 진하게 (텍스트 대비 4.5:1)
    val Card = Color(0xFFFFFFFF)
    val OnCherry = Color(0xFFFFFDF5)
    val Line = Color(0xFFECE4CC)
}

val LogoFont = FontFamily(Font(R.font.caveat_brush))

@Composable
fun SauceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Sauce.Cherry,
            onPrimary = Sauce.OnCherry,
            secondary = Sauce.Teal,
            onSecondary = Sauce.Ink,
            background = Sauce.Bg,
            onBackground = Sauce.Ink,
            surface = Sauce.Card,
            onSurface = Sauce.Ink,
        ),
        content = content,
    )
}
