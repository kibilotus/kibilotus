package com.kibilotus.supplymanager.ui.common

import androidx.annotation.StringRes
import com.kibilotus.supplymanager.R
import com.kibilotus.supplymanager.data.SaveResult

/** [SaveResult] → 사용자에게 보여줄 문구. 성공이면 null. */
object ErrorMessages {

    @StringRes
    fun forFolder(result: SaveResult): Int? = when (result) {
        SaveResult.SUCCESS -> null
        SaveResult.BLANK_NAME -> R.string.error_blank_name
        SaveResult.DUPLICATE_NAME -> R.string.error_duplicate_folder
        SaveResult.INVALID_QUANTITY -> R.string.error_invalid_quantity
        SaveResult.INVALID_COST -> R.string.error_invalid_cost
        SaveResult.NOT_FOUND -> R.string.error_not_found
    }

    @StringRes
    fun forItem(result: SaveResult): Int? = when (result) {
        SaveResult.DUPLICATE_NAME -> R.string.error_duplicate_item
        else -> forFolder(result)
    }

    @StringRes
    fun forPurchase(result: SaveResult): Int? = forFolder(result)
}
