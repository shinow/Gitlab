package org.springblade.contract.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.springblade.contract.vo.ContractPerformanceColPayRequestVO;
import org.springblade.core.mp.base.BaseService;
import org.springblade.contract.entity.ContractPerformanceColPayEntity;

/**
 * 收付款计划清单-收付款 服务类
 *
 * @author szw
 * @date : 2020-11-05 17:07:02
 */
public interface IContractPerformanceColPayService extends BaseService<ContractPerformanceColPayEntity> {

	/**
	 * 分页查询
	 * @param page
	 * @param contractPerformanceColPay
	 * @return
	 */
	IPage<ContractPerformanceColPayEntity> pageList(IPage<ContractPerformanceColPayEntity> page, ContractPerformanceColPayRequestVO contractPerformanceColPay);
}