package com.yadony.api.admin.dto;

import com.yadony.api.signalements.ReportAction;

public record ResolveReportRequest(
        ReportAction action,
        String note
) {
}
