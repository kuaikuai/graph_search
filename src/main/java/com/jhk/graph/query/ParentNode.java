package com.jhk.graph.query;

import org.apache.jena.rdf.model.Resource;

/**
 * 记录路径中指向当前类的父节点
 * @param parentClass 父节点类 Resource
 * @param property    链接父子类的属性 Resource
 * @param depth       从目标类到父节点的深度
 */
public record ParentNode(Resource parentClass, Resource property, int depth) {
    public ParentNode(Resource parentClass, Resource property) {
        this(parentClass, property, 0);
    }
}
