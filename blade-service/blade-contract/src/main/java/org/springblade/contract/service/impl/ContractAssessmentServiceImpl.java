package org.springblade.contract.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.springblade.core.mp.base.BaseServiceImpl;
import org.springblade.contract.entity.ContractAssessmentEntity;
import org.springblade.contract.mapper.ContractAssessmentMapper;
import org.springblade.contract.service.IContractAssessmentService;
import org.springframework.stereotype.Service;

/**
 * 合同评估表 服务实现类
 *
 * @author liyj
 * @date : 2020-09-23 23:28:31
 */
@Service
public class ContractAssessmentServiceImpl extends BaseServiceImpl<ContractAssessmentMapper, ContractAssessmentEntity> implements IContractAssessmentService {

	@Override
	public IPage<ContractAssessmentEntity> pageList(IPage<ContractAssessmentEntity> page, ContractAssessmentEntity assessment) {
		return baseMapper.pageList(page, assessment);
	}
}
