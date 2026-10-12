package com.example.be.domain.companyinterest;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.List;

public final class CompanyInterestSelectionTestSupport {
    private CompanyInterestSelectionTestSupport() { }
    public static CompanyInterestSelection passThrough() {
        var selection = mock(CompanyInterestSelection.class);
        when(selection.select(anyList(), nullable(Long.class))).thenAnswer(call ->
                new CompanyInterestSelection.Result(call.getArgument(0), List.of()));
        when(selection.selectWeekly(any())).thenAnswer(call ->
                new CompanyInterestSelection.WeeklyResult(call.getArgument(0), List.of()));
        return selection;
    }
}
