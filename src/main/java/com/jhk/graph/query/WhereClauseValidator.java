package com.jhk.graph.query;

import com.jhk.graph.dto.request.PathElement;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Pattern 查询 where 子句验证器
 *
 * 验证 where 子句中引用的变量是否在 path 的 select 列表或别名中定义
 */
public class WhereClauseValidator {

    /**
     * 验证 where 子句的变量引用是否有效
     *
     * @param whereClause where 子句表达式（如 "FILTER(?company.employees > 100)"）
     * @param selectVars select 列表（可为 null，表示返回全部）
     * @param path path 元素数组
     * @return 验证结果
     */
    public static ValidationResult validate(String whereClause, String[] selectVars, PathElement[] path) {
        ValidationResult result = new ValidationResult();

        if (whereClause == null || whereClause.isBlank()) {
            result.setValid(true);
            return result;
        }

        // 1. 提取 where 子句中的所有变量
        Set<String> whereVars = extractVariables(whereClause);
        if (whereVars.isEmpty()) {
            result.setValid(true);
            return result;
        }

        // 2. 收集 path 中定义的所有变量名
        Set<String> definedVars = collectDefinedVariables(path);

        // 3. 如果 select 不为空，添加 select 中的变量
        if (selectVars != null) {
            for (String var : selectVars) {
                definedVars.add("?" + var);
            }
        }

        // 4. 验证所有 where 变量是否在 definedVars 中
        for (String whereVar : whereVars) {
            if (!definedVars.contains(whereVar)) {
                result.addError("Variable " + whereVar + " in where clause is not defined in path or select");
            }
        }

        result.setValid(result.getErrors().isEmpty());
        return result;
    }

    /**
     * 从文本中提取所有 SPARQL 变量（?开头的标识符）
     */
    static Set<String> extractVariables(String text) {
        Set<String> vars = new HashSet<>();
        if (text == null || text.isBlank()) {
            return vars;
        }

        Pattern pattern = Pattern.compile("\\?([a-zA-Z_][a-zA-Z0-9_]*)");
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            vars.add("?" + matcher.group(1));
        }
        return vars;
    }

    /**
     * 收集 path 中定义的所有变量名
     */
    private static Set<String> collectDefinedVariables(PathElement[] path) {
        Set<String> vars = new HashSet<>();
        if (path == null) {
            return vars;
        }

        for (PathElement elem : path) {
            if (elem.isEdge()) {
                // 边可能有别名
                if (elem.getAs() != null && !elem.getAs().isBlank()) {
                    vars.add("?" + elem.getAs());
                }
            } else {
                // 节点有 type 和可能的 filters
                if (elem.getAs() != null && !elem.getAs().isBlank()) {
                    vars.add("?" + elem.getAs());
                }
            }
        }

        return vars;
    }

    /**
     * 验证结果
     */
    public static class ValidationResult {
        private boolean valid;
        private final List<String> errors;

        public ValidationResult() {
            this.valid = false;
            this.errors = new java.util.ArrayList<>();
        }

        public void setValid(boolean valid) {
            this.valid = valid;
        }

        public boolean isValid() {
            return valid;
        }

        public void addError(String error) {
            this.errors.add(error);
        }

        public List<String> getErrors() {
            return errors;
        }

        public String getErrorMessage() {
            return String.join("; ", errors);
        }
    }
}
