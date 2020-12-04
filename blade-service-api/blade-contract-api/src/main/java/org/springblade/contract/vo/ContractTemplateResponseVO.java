package org.springblade.contract.vo;

import lombok.*;
import org.springblade.contract.entity.ContractTemplateEntity;
import io.swagger.annotations.ApiModel;
import org.springblade.resource.vo.FileVO;
import org.springblade.system.entity.TemplateEntity;

import java.util.List;

/**
 * 范本管理 返回模型VO
 *
 * @author XHB
 * @date : 2020-09-24 13:57:40
 */
@Getter
@Setter
@NoArgsConstructor
@ApiModel(description = "范本管理返回对象")
@EqualsAndHashCode(callSuper = true)
public class ContractTemplateResponseVO extends ContractTemplateEntity {

	private static final long serialVersionUID = 1L;
	/**
	 *范本附件列表
	 */
	private List<FileVO> templateFileVOList;
	/**
	 * 范本历史版本
	 */
	private List<ContractTemplateEntity> templateEntityOldVOList;

	private String createUserName;

	private String createDeptName;

	private String updateUserName;

	private String userRealName;

	private String userDepartName;
}
