package com.daxiang.core.testng;

import com.alibaba.fastjson.JSONObject;
import com.daxiang.action.BaseAction;
import com.daxiang.model.action.*;
import com.daxiang.model.devicetesttask.DeviceTestTask;
import com.daxiang.model.devicetesttask.Testcase;
import freemarker.template.TemplateException;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by jiangyitao.
 */
public abstract class TestNGCodeConverter {

    private static final String FTL_BASE_PACKAGE_PATH = "/codetemplate";
    private static final String FTL_FILE_NAME = "index.ftl";

    private static final String ACTION_PREFIX = "action_";
    private static final String TESTCASE_PREFIX = "testcase_";
    /**
     * actionId: Action
     */
    private final Map<Integer, Action> cachedActions = new HashMap<>();

    private final Set<String> javaImports = new HashSet<>();

    /**
     * 转换为testng代码
     */
    public String convert(DeviceTestTask deviceTestTask, String className) throws TestNGCodeConvertException {
        Map<String, Object> dataModel = new HashMap<>();

        List<Testcase> testcases = deviceTestTask.getTestcases();
        dataModel.put("testcases", testcases.stream().map(testcase -> {
            JSONObject tc = new JSONObject();
            tc.put("invoke", getInvokeMethodStringWithParamNull(testcase));
            tc.put("description", getTestcaseDesc(deviceTestTask, testcase));
            tc.put("dependsOnMethods", getTestcaseDependsOnMethods(testcase.getDepends()));
            tc.put("id", testcase.getId());
            return tc;
        }).collect(Collectors.toList()));

        List<Action> actions = new ArrayList<>(testcases);

        Action beforeClass = deviceTestTask.getBeforeClass();
        if (beforeClass != null) {
            actions.add(beforeClass);
            String invokeBeforeClass = getInvokeMethodStringWithParamNull(beforeClass);
            dataModel.put("beforeClass", invokeBeforeClass);
        }

        Action afterClass = deviceTestTask.getAfterClass();
        if (afterClass != null) {
            actions.add(afterClass);
            String invokeAfterClass = getInvokeMethodStringWithParamNull(afterClass);
            dataModel.put("afterClass", invokeAfterClass);
        }

        Action beforeMethod = deviceTestTask.getBeforeMethod();
        if (beforeMethod != null) {
            actions.add(beforeMethod);
            String invokeBeforeMethod = getInvokeMethodStringWithParamNull(beforeMethod);
            dataModel.put("beforeMethod", invokeBeforeMethod);
        }

        Action afterMethod = deviceTestTask.getAfterMethod();
        if (afterMethod != null) {
            actions.add(afterMethod);
            String invokeAfterMethod = getInvokeMethodStringWithParamNull(afterMethod);
            dataModel.put("afterMethod", invokeAfterMethod);
        }

        parseActions(actions);
        cachedActions.remove(BaseAction.EXECUTE_JAVA_CODE_ID); // ExecuteJavaCode无需调用
        handleActionValue();
        dataModel.put("actions", cachedActions.values());

        handleGlobalVarValue(deviceTestTask.getGlobalVars());

        dataModel.put("className", className);
        dataModel.put("actionPrefix", ACTION_PREFIX);
        dataModel.put("testcasePrefix", TESTCASE_PREFIX);
        dataModel.put("executeJavaCodeActionId", BaseAction.EXECUTE_JAVA_CODE_ID);

        dataModel.put("driverClassSimpleName", getDriverClass().getSimpleName());
        dataModel.put("actionClassSimpleName", getActionClass().getSimpleName());
        dataModel.put("deviceClassSimpleName", getDeviceClass().getSimpleName());

        handleJavaImports();
        dataModel.put("javaImports", javaImports);

        dataModel.put("deviceTestTask", deviceTestTask);

        try {
            return FreemarkerUtil.process(FTL_BASE_PACKAGE_PATH, FTL_FILE_NAME, dataModel);
        } catch (IOException | TemplateException e) {
            throw new TestNGCodeConvertException(e);
        }
    }

    protected abstract Class getDriverClass();

    protected abstract Class getActionClass();

    protected abstract Class getDeviceClass();

    protected abstract void addJavaImports(Set<String> javaImports);

    private String getTestcaseDesc(DeviceTestTask deviceTestTask, Testcase testcase) {
        if (deviceTestTask.getId() == null) { // 调试
            return null;
        }

        String deviceId = deviceTestTask.getDeviceId();
        Integer deviceTestTaskId = deviceTestTask.getId();
        Integer testcaseId = testcase.getId();
        Integer enableRecordVideo = deviceTestTask.getTestPlan().getEnableRecordVideo();
        Integer failRetryCount = deviceTestTask.getTestPlan().getFailRetryCount();

        return String.format("%s_%d_%d_%d_%d", deviceId, deviceTestTaskId, testcaseId, enableRecordVideo, failRetryCount);
    }

    private String getTestcaseDependsOnMethods(List<Integer> depends) {
        if (CollectionUtils.isEmpty(depends)) {
            return null;
        }

        // {"action_2","action_1"}
        return "{" + depends.stream().map(id -> "\"" + TESTCASE_PREFIX + id + "\"").collect(Collectors.joining(",")) + "}";
    }

    private void handleJavaImports() {
        javaImports.add("import com.daxiang.core.testng.TestCaseTestListener");
        javaImports.add("import com.daxiang.core.testng.DebugActionTestListener");

        javaImports.add("import com.daxiang.core.Device");
        javaImports.add("import com.daxiang.core.DeviceHolder");

        javaImports.add("import org.testng.annotations.*");
        javaImports.add("import org.testng.SkipException");

        javaImports.add("import io.appium.java_client.pagefactory.*"); // AppiumFieldDecorator同样适用于pc web

        javaImports.add("import org.openqa.selenium.*");
        javaImports.add("import org.openqa.selenium.support.*");

        javaImports.add("import java.util.*");
        javaImports.add("import static org.assertj.core.api.Assertions.*");

        addJavaImports(javaImports);

        cachedActions.values().forEach(action -> {
            List<String> actionJavaImports = action.getJavaImports();
            if (!CollectionUtils.isEmpty(actionJavaImports)) {
                javaImports.addAll(actionJavaImports);
            }
        });
    }

    private String getInvokeMethodStringWithParamNull(Action action) {
        StringBuilder invokeMethod = new StringBuilder(ACTION_PREFIX + action.getId() + "(");
        List<Param> actionParams = action.getParams();
        // 如果有参数 则都传入null
        if (!CollectionUtils.isEmpty(actionParams)) {
            invokeMethod.append(actionParams.stream().map(i -> "null").collect(Collectors.joining(",")));
        }
        invokeMethod.append(");");
        return invokeMethod.toString();
    }

    /**
     * 递归把所有action放到cachedActions里
     */
    private void parseActions(List<Action> actions) {
        for (Action action : actions) {
            Action cachedAction = cachedActions.get(action.getId());
            if (cachedAction == null) {
                // steps
                List<Step> steps = action.getSteps();
                if (!CollectionUtils.isEmpty(steps)) {
                    for (Step step : steps) {
                        Action stepAction = step.getAction();
                        if (stepAction != null) {
                            parseActions(Arrays.asList(stepAction));
                        }
                    }
                }

                // importActions
                List<Action> importActions = action.getImportActions();
                if (!CollectionUtils.isEmpty(importActions)) {
                    parseActions(importActions);
                }

                cachedActions.put(action.getId(), action);
            }
        }
    }

    /**
     * 处理globalVar value
     */
    private void handleGlobalVarValue(List<GlobalVar> globalVars) {
        if (!CollectionUtils.isEmpty(globalVars)) {
            globalVars.forEach(globalVar -> globalVar.setValue(handleValue(globalVar.getValue())));
        }
    }

    /**
     * 处理action localVar value & step args
     */
    private void handleActionValue() {
        Collection<Action> actions = cachedActions.values();
        for (Action action : actions) {
            List<LocalVar> localVars = action.getLocalVars();
            if (!CollectionUtils.isEmpty(localVars)) {
                localVars.forEach(localVar -> localVar.setValue(handleValue(localVar.getValue())));
            }

            List<Step> steps = action.getSteps();
            if (!CollectionUtils.isEmpty(steps)) {
                for (Step step : steps) {
                    // ExecuteJavaCode直接嵌入模版，无需处理
                    if (step.getActionId() != BaseAction.EXECUTE_JAVA_CODE_ID) {
                        List<String> args = step.getArgs();
                        if (!CollectionUtils.isEmpty(args)) {
                            List<String> newArgs = args.stream().map(this::handleValue).collect(Collectors.toList());
                            step.setArgs(newArgs);
                        }
                    }
                }
            }
        }
    }

    private String handleValue(String value) {
        if (StringUtils.isEmpty(value)) {
            return "null";
        }

        if (value.startsWith("${") && value.endsWith("}")) {
            return value.substring(2, value.length() - 1);
        } else { // 普通字符串
            return "\"" + value + "\"";
        }
    }
}
