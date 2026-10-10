package com.frostbite.reposilite.quota;

import java.util.Locale;
import java.util.Set;

enum OperationClass {
    A, B, FREE;

    private static final Set<String> CLASS_A = Set.of(
        "listbuckets", "putbucket", "createbucket", "listobjects", "listobjectsv2",
        "putobject", "copyobject", "completemultipartupload", "createmultipartupload",
        "lifecyclestoragetiertransition", "listmultipartuploads", "uploadpart",
        "uploadpartcopy", "listparts", "putbucketencryption", "putbucketcors",
        "putbucketlifecycleconfiguration");
    private static final Set<String> CLASS_B = Set.of(
        "headbucket", "headobject", "getobject", "usagesummary", "getbucketencryption",
        "getbucketlocation", "getbucketcors", "getbucketlifecycleconfiguration");
    private static final Set<String> FREE_OPERATIONS = Set.of(
        "deleteobject", "deleteobjects", "deletebucket", "abortmultipartupload");

    static OperationClass classify(String operation) {
        String name = operation.replaceFirst("Request$", "")
            .replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        if (CLASS_A.contains(name)) return A;
        if (CLASS_B.contains(name)) return B;
        if (FREE_OPERATIONS.contains(name)) return FREE;
        // Unknown operations must never silently bypass either budget.
        throw new IllegalArgumentException("Unclassified R2 operation: " + operation);
    }
}
