package com.example.be.domain.analysis.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "news.analysis")
public class AnalysisSelectionProperties implements InitializingBean {

    private int issueLimitPerRun = 30;
    private Sensitivity sensitivity = new Sensitivity();

    @Override
    public void afterPropertiesSet() {
        if (issueLimitPerRun <= 0) {
            throw new IllegalStateException("news.analysis.issue-limit-per-run은 1 이상이어야 합니다.");
        }
        sensitivity.validate();
    }

    public int getIssueLimitPerRun() {
        return issueLimitPerRun;
    }

    public void setIssueLimitPerRun(int issueLimitPerRun) {
        this.issueLimitPerRun = issueLimitPerRun;
    }

    public Sensitivity getSensitivity() {
        return sensitivity;
    }

    public void setSensitivity(Sensitivity sensitivity) {
        this.sensitivity = sensitivity;
    }

    public static class Sensitivity {

        private BigDecimal customerMoveWeight = new BigDecimal("0.35");
        private BigDecimal dealSignalWeight = new BigDecimal("0.30");
        private BigDecimal competitorThreatWeight = new BigDecimal("0.20");
        private BigDecimal industryShiftWeight = new BigDecimal("0.15");
        private BigDecimal mediumThreshold = new BigDecimal("40");
        private BigDecimal highThreshold = new BigDecimal("70");

        void validate() {
            BigDecimal total = customerMoveWeight.add(dealSignalWeight)
                    .add(competitorThreatWeight).add(industryShiftWeight);
            if (customerMoveWeight.signum() <= 0 || dealSignalWeight.signum() <= 0
                    || competitorThreatWeight.signum() <= 0 || industryShiftWeight.signum() <= 0
                    || total.compareTo(BigDecimal.ONE) != 0) {
                throw new IllegalStateException("민감도 축 가중치 합은 1이어야 합니다.");
            }
            if (mediumThreshold.signum() < 0 || highThreshold.compareTo(mediumThreshold) <= 0
                    || highThreshold.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalStateException("민감도 임계값은 0 <= medium < high <= 100이어야 합니다.");
            }
        }

        public BigDecimal getCustomerMoveWeight() { return customerMoveWeight; }
        public void setCustomerMoveWeight(BigDecimal value) { this.customerMoveWeight = value; }
        public BigDecimal getDealSignalWeight() { return dealSignalWeight; }
        public void setDealSignalWeight(BigDecimal value) { this.dealSignalWeight = value; }
        public BigDecimal getCompetitorThreatWeight() { return competitorThreatWeight; }
        public void setCompetitorThreatWeight(BigDecimal value) { this.competitorThreatWeight = value; }
        public BigDecimal getIndustryShiftWeight() { return industryShiftWeight; }
        public void setIndustryShiftWeight(BigDecimal value) { this.industryShiftWeight = value; }
        public BigDecimal getMediumThreshold() { return mediumThreshold; }
        public void setMediumThreshold(BigDecimal value) { this.mediumThreshold = value; }
        public BigDecimal getHighThreshold() { return highThreshold; }
        public void setHighThreshold(BigDecimal value) { this.highThreshold = value; }
    }
}
