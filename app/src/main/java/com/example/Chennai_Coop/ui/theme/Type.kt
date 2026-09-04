package com.example.Chennai_Coop.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.Chennai_Coop.R

val OpenSansFontFamily = FontFamily(
    Font(R.font.open_sans_regular, FontWeight.Normal),
    Font(R.font.open_sans_semibold, FontWeight.Medium),
    Font(R.font.open_sans_semibold, FontWeight.SemiBold),
    Font(R.font.open_sans_bold, FontWeight.Bold)
)

private fun TextStyle.withOpenSans() = copy(fontFamily = OpenSansFontFamily)

private val MaterialTypography = Typography()

/** Applies bundled Open Sans to every Material 3 text role in the app. */
val AppTypography = Typography(
    displayLarge = MaterialTypography.displayLarge.withOpenSans(),
    displayMedium = MaterialTypography.displayMedium.withOpenSans(),
    displaySmall = MaterialTypography.displaySmall.withOpenSans(),
    headlineLarge = MaterialTypography.headlineLarge.withOpenSans(),
    headlineMedium = MaterialTypography.headlineMedium.withOpenSans(),
    headlineSmall = MaterialTypography.headlineSmall.withOpenSans(),
    titleLarge = MaterialTypography.titleLarge.withOpenSans(),
    titleMedium = MaterialTypography.titleMedium.withOpenSans(),
    titleSmall = MaterialTypography.titleSmall.withOpenSans(),
    bodyLarge = MaterialTypography.bodyLarge.withOpenSans(),
    bodyMedium = MaterialTypography.bodyMedium.withOpenSans(),
    bodySmall = MaterialTypography.bodySmall.withOpenSans(),
    labelLarge = MaterialTypography.labelLarge.withOpenSans(),
    labelMedium = MaterialTypography.labelMedium.withOpenSans(),
    labelSmall = MaterialTypography.labelSmall.withOpenSans()
)
