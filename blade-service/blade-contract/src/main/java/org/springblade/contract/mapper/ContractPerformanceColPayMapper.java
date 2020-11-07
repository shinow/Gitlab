package org.springblade.contract.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.springblade.contract.entity.ContractPerformanceColPayEntity;
import org.springblade.contract.vo.ContractPerformanceColPayRequestVO;

/**
 * 收付款计划清单-收付款 Mapper 接口
 *
 * @author szw
 * @date : 2020-11-05 17:07:02
 */
public interface ContractPerformanceColPayMapper extends BaseMapper<ContractPerformanceColPayEntity> {

	/**
	 * 分页查询
	 * @param page
	 * @param contractPerformanceColPay
	 * @return
	 */
	IPage<ContractPerformanceColPayEntity> pageList(IPage<ContractPerformanceColPayEntity> page, ContractPerformanceColPayRequestVO contractPerformanceColPay);

}
