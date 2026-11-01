package org.example.takeout.Settlement.Mapper;

import org.example.takeout.Settlement.Entity.IncomeRecord;
import org.example.takeout.Settlement.Enums.BeneficiaryTypeEnum;
import org.example.takeout.Settlement.Enums.IncomeRecordTypeEnum;
import org.example.takeout.Settlement.VO.RewardTransactionVO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class IncomeRecordConverterTest {
    private final IncomeRecordConverter converter = new IncomeRecordConverterImpl();

    @Test
    void mapsLedgerRecordFieldsToTheRewardTransactionVo() {
        IncomeRecord record = new IncomeRecord();
        record.setId(7L);
        record.setBeneficiaryType(BeneficiaryTypeEnum.RIDER.getCode());
        record.setBeneficiaryId(301L);
        record.setOrderId(601L);
        record.setType(IncomeRecordTypeEnum.DELIVERY_REWARD.getCode());
        record.setAmount(new BigDecimal("6.25"));
        record.setCreateTime(LocalDateTime.of(2026, 10, 30, 12, 0));

        RewardTransactionVO vo = converter.toRewardTransactionVO(record);

        assertThat(vo.getId()).isEqualTo(7L);
        assertThat(vo.getBeneficiaryType()).isEqualTo(BeneficiaryTypeEnum.RIDER.getCode());
        assertThat(vo.getOrderId()).isEqualTo(601L);
        assertThat(vo.getType()).isEqualTo(IncomeRecordTypeEnum.DELIVERY_REWARD.getCode());
        assertThat(vo.getAmount()).isEqualByComparingTo("6.25");
        assertThat(vo.getCreateTime()).isEqualTo(LocalDateTime.of(2026, 10, 30, 12, 0));
    }
}
