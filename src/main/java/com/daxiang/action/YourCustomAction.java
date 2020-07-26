package com.daxiang.action;

import com.daxiang.core.action.annotation.Action;
import com.daxiang.core.action.annotation.Param;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.util.StringUtils;

/**
 * Created by jiangyitao.
 * id: 5000 - 10000
 */
public class YourCustomAction {

    /**
     * invoke = 2 将通过YourCustomAction.randomAlphanumeric 进行调用
     */
    @Action(id = 5000, name = "随机字符串（数字 & 字母）", invoke = 2)
    public static String randomAlphanumeric(@Param(description = "字符串长度") String count) {
        if (StringUtils.isEmpty(count)) {
            count = "10";
        }
        return RandomStringUtils.randomAlphanumeric(Integer.parseInt(count));
    }

}
