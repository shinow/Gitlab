package org.springblade.contract.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.*;
import org.springblade.core.mp.base.BaseEntity;

/**
 * 合同评估表 请求模型VO
 *
 * @NotNull 验证对象是否不为null, 无法查检长度为0的字符串
 * @NotBlank 检查约束 (字符串) 是不是Null还有被Trim的长度是否大于0,只对字符串,且会去掉前后空格.
 * @NotEmpty 检查(集合)约束元素是否为NULL或者是EMPTY.
 *
 * @author liyj
 * @date : 2020-09-23 15:50:00
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ApiModel(description = "合同评估表请求对象")
public class AssessmentRequestVO extends BaseEntity {

	private static final long serialVersionUID = 1L;

    @ApiModelProperty(value="相关附件")
	private String attachedFiles;
	
    @ApiModelProperty(value="评估说明")
	private String assessmentRemark;
	
    @ApiModelProperty(value="经办人的所有信息")
	private String allInformation;
	
    @ApiModelProperty(value="合同id")
	private Long contractId;
	
}
