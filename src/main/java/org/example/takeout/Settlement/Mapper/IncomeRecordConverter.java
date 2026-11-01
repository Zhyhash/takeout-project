package org.example.takeout.Settlement.Mapper;

import org.example.takeout.Settlement.Entity.IncomeRecord;
import org.example.takeout.Settlement.VO.RewardTransactionVO;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface IncomeRecordConverter {

    RewardTransactionVO toRewardTransactionVO(IncomeRecord incomeRecord);
}
