/*
 *      Copyright (c) 2018-2028, Chill Zhuang All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 *  Redistributions of source code must retain the above copyright notice,
 *  this list of conditions and the following disclaimer.
 *  Redistributions in binary form must reproduce the above copyright
 *  notice, this list of conditions and the following disclaimer in the
 *  documentation and/or other materials provided with the distribution.
 *  Neither the name of the dreamlu.net developer nor the names of its
 *  contributors may be used to endorse or promote products derived from
 *  this software without specific prior written permission.
 *  Author: Chill 庄骞 (smallchill@163.com)
 */
package org.springblade.system.service;


import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springblade.core.mp.support.Query;
import org.springblade.system.entity.DictBiz;
import org.springblade.system.vo.DictBizVO;

import java.util.List;
import java.util.Map;

/**
 * 服务类
 *
 * @author Chill
 */
public interface IDictBizService extends IService<DictBiz> {

	/**
	 * 树形结构
	 *
	 * @return
	 */
	List<DictBizVO> tree(String code);

	/**
	 * 树形结构map
	 *
	 * @return
	 */
	Map<String, List<DictBizVO>>  treeMap(List<String> codes);

	/**
	 * 树形结构
	 *
	 * @return
	 */
	List<DictBizVO> parentTree();

	/**
	 * 获取字典表对应中文
	 *
	 * @param code    字典编号
	 * @param dictKey 字典序号
	 * @return
	 */
	String getValue(String code, String dictKey);
	/**
	 * 获取字典表对键值
	 *
	 * @param code    字典编号
	 * @param dictValue 字典序号
	 * @return
	 */
	String getKey(String code, String dictValue);
	/**
	 * 获取字典表对应中文
	 *
	 * @param code    字典编号
	 * @param id 父主键
	 * @return
	 */
	String getValues(String code, Long id);
	/**
	 * 获取字典表
	 *
	 * @param code 字典编号
	 * @return
	 */
	List<DictBiz> getList(String code);

	/**
	 * 新增或修改
	 *
	 * @param dict
	 * @return
	 */
	boolean submit(DictBiz dict);

	/**
	 * 删除字典
	 *
	 * @param ids
	 * @return
	 */
	boolean removeDict(String ids);

	/**
	 * 顶级列表
	 * @param dict
	 * @param query
	 * @return
	 */
	IPage<DictBizVO> parentList(Map<String, Object> dict, Query query);

	/**
	 * 子列表
	 * @param dict
	 * @param parentId
	 * @param query
	 * @return
	 */
	IPage<DictBizVO> childList(Map<String, Object> dict, Long parentId, Query query);

	/**
	 * 懒加载列表
	 *
	 * @param parentId
	 * @param param
	 * @return
	 */
	List<DictBizVO> lazyList(Long parentId, Map<String, Object> param);

	/**
	 * 删除子节点
	 *
	 * @param ids
	 * @return Boolean
	 */
	Boolean deleteIds(String ids);

	/**
	 * 根据codes获取字典(code以“，”分隔)
	 * @param codes
	 * @return
	 */
	Map<String, List<DictBiz>> dictionaryByCodes(List<String> codes);
	/**
	 * 分页查询
	 * @param page
	 * @param dict
	 * @return
	 */
	IPage<DictBiz> pageList(IPage<DictBiz> page, DictBiz dict);
}
