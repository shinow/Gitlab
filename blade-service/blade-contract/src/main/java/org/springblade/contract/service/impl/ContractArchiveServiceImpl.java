package org.springblade.contract.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.springblade.contract.mapper.ContractFormInfoMapper;
import org.springblade.contract.service.IContractArchiveService;
import org.springblade.contract.service.IContractFormInfoService;
import org.springblade.contract.vo.ContractArchiveRequestVO;
import org.springblade.contract.wrapper.ContractFormInfoWrapper;
import org.springblade.core.mp.base.BaseServiceImpl;
import org.springblade.contract.entity.ContractArchiveEntity;
import org.springblade.contract.mapper.ContractArchiveMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 合同归档管理 服务实现类
 *
 * @author XHB
 * @date : 2020-09-23 18:32:15
 */
@Service
public class ContractArchiveServiceImpl extends BaseServiceImpl<ContractArchiveMapper, ContractArchiveEntity> implements IContractArchiveService {

	@Resource
	private IContractFormInfoService contractFormInfoService;
	@Resource
	private ContractFormInfoMapper formInfoMapper;

	@Override
	public IPage<ContractArchiveEntity> pageList(IPage<ContractArchiveEntity> page, ContractArchiveEntity archive) {
		return baseMapper.pageList(page, archive);
	}
	/**
	 *保存归档信息与修改合同状态
	 * @param contractStatus
	 * @param entity
	 * @return
	 */
	@Override
	public boolean save(String contractStatus, ContractArchiveEntity entity) {
		contractFormInfoService.updateExportStatus(contractStatus,entity.getContractId());
		if(baseMapper.insert(entity) == 1) {
			return true;
		}
		return false;
	}

	/**
	 * 保存合同归档对应数据id
	 * @param vo
	 */
	@Override
	public void saveArchive(ContractArchiveRequestVO vo) {
		formInfoMapper.saveArchive(vo.getContractId(),vo.getId());
	}

}
