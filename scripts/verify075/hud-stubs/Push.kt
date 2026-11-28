package net.ccbluex.liquidbounce.render
inline fun org.joml.Matrix3x2fStack.withPush(block:org.joml.Matrix3x2fStack.()->Unit) { pushMatrix();try{block()}finally{popMatrix()} }
