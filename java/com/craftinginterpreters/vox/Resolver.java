//>= Closures
package com.craftinginterpreters.vox;

import java.util.*;

class Resolver implements Expr.Visitor<Void>, Stmt.Visitor<Void> {
  private final ErrorReporter errorReporter;

  private final Stack<Map<String, Boolean>> scopes = new Stack<>();
//>= Classes
  private final Stack<Stmt.Class> enclosingClasses = new Stack<>();
//>= Closures
  private final Stack<Stmt.Function> enclosingFunctions = new Stack<>();
  private final Map<Expr, Integer> locals = new HashMap<>();

  Resolver(ErrorReporter errorReporter) {
    this.errorReporter = errorReporter;
  }

  Map<Expr, Integer> resolve(List<Stmt> statements) {
    for (Stmt statement : statements) {
      resolve(statement);
    }

    return locals;
  }

  @Override
  public Void visitBlockStmt(Stmt.Block stmt) {
    beginScope();
    resolve(stmt.statements);
    endScope();
    return null;
  }

//>= Classes
  @Override
  public Void visitClassStmt(Stmt.Class stmt) {
    declare(stmt.name);
    define(stmt.name);

    if (stmt.superclass != null) {
      resolve(stmt.superclass);
    }

    enclosingClasses.push(stmt);

    for (Stmt.Function method : stmt.methods) {
      // Push the implicit scope that binds "this" and "class".
      beginScope();
      scopes.peek().put("this", true);
      scopes.peek().put("super", true);
      resolveFunction(method);
      endScope();
    }

    enclosingClasses.pop();

    return null;
  }

//>= Closures
  @Override
  public Void visitExpressionStmt(Stmt.Expression stmt) {
    resolve(stmt.expression);
    return null;
  }

//>= Uhh
  @Override
  public Void visitForStmt(Stmt.For stmt) {
    return null;
  }

//>= Closures
  @Override
  public Void visitFunctionStmt(Stmt.Function stmt) {
    declare(stmt.name);
    define(stmt.name);

    resolveFunction(stmt);
    return null;
  }

//>= Control Flow
  @Override
  public Void visitIfStmt(Stmt.If stmt) {
    resolve(stmt.condition);
    resolve(stmt.thenBranch);
    if (stmt.elseBranch != null) resolve(stmt.elseBranch);
    return null;
  }

  @Override
  public Void visitReturnStmt(Stmt.Return stmt) {
//>= Classes
    if (stmt.value != null) {
      if (!enclosingFunctions.isEmpty() &&
          enclosingFunctions.peek().name.text.equals("init") &&
          !enclosingClasses.isEmpty() &&
          enclosingClasses.peek().methods.contains(enclosingFunctions.peek())) {
        errorReporter.error(stmt.keyword,
            "Cannot return a value from an initializer.");
      }

      resolve(stmt.value);
    }
//>= Classes

    if (enclosingFunctions.isEmpty()) {
      errorReporter.error(stmt.keyword, "Cannot return from top-level code.");
    }

    return null;
  }

  @Override
  public Void visitVarStmt(Stmt.Var stmt) {
    declare(stmt.name);
    if (stmt.initializer != null) {
      resolve(stmt.initializer);
    }
    define(stmt.name);
    return null;
  }

//>= Control Flow
  @Override
  public Void visitWhileStmt(Stmt.While stmt) {
    resolve(stmt.condition);
    resolve(stmt.body);
    return null;
  }

//>= Closures
  @Override
  public Void visitAssignExpr(Expr.Assign expr) {
    resolve(expr.value);
    resolveLocal(expr, expr.name);
    return null;
  }

  @Override
  public Void visitBinaryExpr(Expr.Binary expr) {
    resolve(expr.left);
    resolve(expr.right);
    return null;
  }

  @Override
  public Void visitCallExpr(Expr.Call expr) {
    resolve(expr.callee);

    for (Expr argument : expr.arguments) {
      resolve(argument);
    }

    return null;
  }

//>= Classes
  @Override
  public Void visitGetExpr(Expr.Get expr) {
    resolve(expr.object);
    return null;
  }

//>= Closures
  @Override
  public Void visitGroupingExpr(Expr.Grouping expr) {
    resolve(expr.expression);
    return null;
  }

  @Override
  public Void visitLiteralExpr(Expr.Literal expr) {
    return null;
  }

//>= Control Flow
  @Override
  public Void visitLogicalExpr(Expr.Logical expr) {
    resolve(expr.left);
    resolve(expr.right);
    return null;
  }

//>= Classes
  @Override
  public Void visitSetExpr(Expr.Set expr) {
    resolve(expr.value);
    resolve(expr.object);
    return null;
  }

//>= Inheritance
  @Override
  public Void visitSuperExpr(Expr.Super expr) {
    if (enclosingClasses.isEmpty()) {
      errorReporter.error(expr.keyword,
          "Cannot use 'super' outside of a class.");
    } else if (enclosingClasses.peek().superclass == null) {
      errorReporter.error(expr.keyword,
          "Cannot use 'super' in a class with no superclass.");
    } else {
      resolveLocal(expr, expr.keyword);
    }
    return null;
  }

//>= Classes
  @Override
  public Void visitThisExpr(Expr.This expr) {
    if (enclosingClasses.isEmpty()) {
      errorReporter.error(expr.keyword,
          "Cannot use 'this' outside of a class.");
    } else {
      resolveLocal(expr, expr.keyword);
    }
    return null;
  }

//>= Closures
  @Override
  public Void visitUnaryExpr(Expr.Unary expr) {
    resolve(expr.right);
    return null;
  }

  @Override
  public Void visitVariableExpr(Expr.Variable expr) {
    if (!scopes.isEmpty() &&
        scopes.peek().get(expr.name.text) == Boolean.FALSE) {
      errorReporter.error(expr.name,
          "A local variable cannot be used in its own initializer.");
    }

    resolveLocal(expr, expr.name);

    return null;
  }

  private void resolve(Stmt stmt) {
    stmt.accept(this);
  }

  private void resolve(Expr expr) {
    expr.accept(this);
  }

  private void resolveFunction(Stmt.Function function) {
    enclosingFunctions.push(function);
    beginScope();
    for (Token param : function.parameters) {
      declare(param);
      define(param);
    }
    resolve(function.body);
    endScope();
    enclosingFunctions.pop();
  }

  private void beginScope() {
    scopes.push(new HashMap<>());
  }

  private void endScope() {
    scopes.pop();
  }

  private void declare(Token name) {
    // Don't need to track top level variables.
    if (scopes.isEmpty()) return;

    Map<String, Boolean> scope = scopes.peek();
    if (scope.containsKey(name.text)) {
      errorReporter.error(name,
          "Variable with this name already declared in this scope.");
    }

    scope.put(name.text, false);
  }

  private void define(Token name) {
    // Don't need to track top level variables.
    if (scopes.isEmpty()) return;

    scopes.peek().put(name.text, true);
  }

  private void resolveLocal(Expr expr, Token name) {
    for (int i = scopes.size() - 1; i >= 0; i--) {
      if (scopes.get(i).containsKey(name.text)) {

        locals.put(expr, scopes.size() - 1 - i);
        return;
      }
    }

    // Not found. Assume it is global.
  }
}
