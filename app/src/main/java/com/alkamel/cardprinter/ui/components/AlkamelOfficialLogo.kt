package com.alkamel.cardprinter.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alkamel.cardprinter.R

@Composable
fun AlkamelOfficialLogo(
    size: Dp = 120.dp,
    modifier: Modifier = Modifier,
    showGlowBorder: Boolean = true
) {
    val cornerRadius = size * 0.24f
    val shape = RoundedCornerShape(cornerRadius)

    val containerModifier = modifier
        .size(size)
        .shadow(
            elevation = if (showGlowBorder) 12.dp else 4.dp,
            shape = shape,
            ambientColor = Color(0xFF1E88E5),
            spotColor = Color(0xFFFF9800)
        )
        .clip(shape)
        .background(Color(0xFF071022))
        .then(
            if (showGlowBorder) {
                Modifier.border(
                    width = 2.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF29B6F6), Color(0xFF1565C0), Color(0xFFFFA000))
                    ),
                    shape = shape
                )
            } else {
                Modifier
            }
        )

    Box(modifier = containerModifier, contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(id = R.drawable.alkamel_official_logo),
            contentDescription = "شعار تطبيق الكامل-طباعة الكروت",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp)
                .clip(RoundedCornerShape((cornerRadius - 2.dp).coerceAtLeast(8.dp)))
        )
    }
}
