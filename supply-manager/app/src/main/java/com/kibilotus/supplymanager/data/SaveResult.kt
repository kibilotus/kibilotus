package com.kibilotus.supplymanager.data

/** 입력 검증 결과. UI 는 실패 사유별로 스낵바 문구를 고른다. */
enum class SaveResult {
    SUCCESS,
    BLANK_NAME,
    DUPLICATE_NAME,
    INVALID_QUANTITY,
    INVALID_COST,
    NOT_FOUND,
}
