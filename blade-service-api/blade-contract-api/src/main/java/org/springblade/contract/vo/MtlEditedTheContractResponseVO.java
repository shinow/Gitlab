package org.springblade.contract.vo;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import org.springblade.contract.entity.MtlEditedTheContractEntity;
import io.swagger.annotations.ApiModel;
import java.util.Date;

/**
 * 媒体类：修图合同 返回模型VO
 *
 * @author 媒体类：修图合同
 * @date : 2020-12-10 19:24:52
 */
@Getter
@Setter
@ToString
@ApiModel(description = "媒体类：修图合同返回对象")
@EqualsAndHashCode(callSuper = true)
public class MtlEditedTheContractResponseVO extends MtlEditedTheContractEntity {

	private static final long serialVersionUID = 1L;

	private String createUserName;

	private String createDeptName;

	private String updateUserName;
}