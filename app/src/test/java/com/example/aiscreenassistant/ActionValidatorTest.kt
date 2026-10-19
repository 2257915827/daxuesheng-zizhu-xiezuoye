package com.example.aiscreenassistant

import com.example.aiscreenassistant.engine.ActionValidator
import com.example.aiscreenassistant.model.*
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionValidatorTest {
    @Test fun rejectsLowConfidenceActions() {
        val result = ActionValidator.validate(listOf(PlannedAction(ActionType.TAP, confidence = .4f)))
        assertTrue(result.isFailure)
    }
    @Test fun acceptsSafeAction() {
        val result = ActionValidator.validate(listOf(PlannedAction(ActionType.INPUT_TEXT, text = "答案", confidence = .9f)))
        assertTrue(result.isSuccess)
    }
}
