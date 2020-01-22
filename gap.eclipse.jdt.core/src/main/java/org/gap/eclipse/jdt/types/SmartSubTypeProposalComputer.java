package org.gap.eclipse.jdt.types;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.ui.text.java.ContentAssistInvocationContext;
import org.eclipse.jdt.ui.text.java.IJavaCompletionProposalComputer;
import org.eclipse.jdt.ui.text.java.JavaContentAssistInvocationContext;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.gap.eclipse.jdt.CorePlugin;

import com.google.common.base.Suppliers;
import com.google.common.collect.Sets;

public class SmartSubTypeProposalComputer extends AbstractSmartProposalComputer implements IJavaCompletionProposalComputer {

	public static final String CATEGORY_ID = "gap.eclipse.jdt.proposalCategory.smartSubType";
	private Set<String> unsupportedTypes = Sets.newHashSet("java.lang.String", "java.lang.Object",
			"java.lang.Cloneable", "java.lang.Throwable", "java.lang.Exception");

	private SubTypeFinder subTypeFinder = new SubTypeFinder();

	private final static long TIMEOUT = Long.getLong("org.eclipse.jdt.ui.codeAssistTimeout", 5000);

	@Override
	public void sessionStarted() {
	}

	@Override
	public List<ICompletionProposal> computeCompletionProposals(ContentAssistInvocationContext invocationContext,
			IProgressMonitor monitor) {
		if (invocationContext instanceof JavaContentAssistInvocationContext) {
			JavaContentAssistInvocationContext context = (JavaContentAssistInvocationContext) invocationContext;
			if (context.getExpectedType() != null) {
				IType expectedType = context.getExpectedType();
				if (unsupportedTypes.contains(expectedType.getFullyQualifiedName())) {
					return Collections.emptyList();
				}
				return completionList(monitor, context, expectedType);
			} else {
				return searchFromAST((JavaContentAssistInvocationContext) context, monitor);
			}
		}
		return Collections.emptyList();
	}

	private List<ICompletionProposal> completionList(IProgressMonitor monitor,
			JavaContentAssistInvocationContext context, IType expectedType) {
		final Duration blockDuration = Duration.ofMillis(TIMEOUT).minusSeconds(2);
		if (isPreceedSpaceNewKeyword(context)) {
			return subTypeFinder.find(expectedType, context, monitor, blockDuration).collect(Collectors.toList());
		}
		return Collections.emptyList();
	}

	private List<ICompletionProposal> searchFromAST(JavaContentAssistInvocationContext context,
			IProgressMonitor monitor) {
		ASTParser parser = ASTParser.newParser(AST.JLS13);
		parser.setSource(context.getCompilationUnit());
		parser.setProject(context.getProject());
		parser.setResolveBindings(true);
		parser.setStatementsRecovery(true);
		parser.setBindingsRecovery(true);
		ASTNode ast = parser.createAST(monitor);
		CompletionASTVistor visitor = new CompletionASTVistor(context);
		ast.accept(visitor);
		final IType expectedType = visitor.getExpectedType();
		if (expectedType == null || unsupportedTypes.contains(expectedType.getFullyQualifiedName())) {
			return Collections.emptyList();
		}
		return completionList(monitor, context, expectedType);
	}

	private boolean isPreceedSpaceNewKeyword(ContentAssistInvocationContext context) {
		final int offset = context.getInvocationOffset();
		final String keywordPrefix = "new ";
		if (offset > keywordPrefix.length()) {
			try {
				return context.getDocument().get(offset - keywordPrefix.length(), keywordPrefix.length())
						.equals(keywordPrefix);
			} catch (BadLocationException e) {
				CorePlugin.getDefault().logError(e.getMessage(), e);
			}
		}
		return false;
	}


	@Override
	public List<IContextInformation> computeContextInformation(ContentAssistInvocationContext context,
			IProgressMonitor monitor) {
		return Collections.emptyList();
	}

	@Override
	public String getErrorMessage() {
		return null;
	}

	@Override
	public void sessionEnded() {

	}

	private class CompletionASTVistor extends ASTVisitor {
		private int offset;
		private IType expectedType;
		private IJavaProject project;
		private boolean preceedSpace = false;

		private CodeRange lastVisited;

		public CompletionASTVistor(JavaContentAssistInvocationContext context) {
			this.offset = context.getInvocationOffset();
			this.project = context.getProject();
			try {
				preceedSpace = context.getDocument().get(offset - 1, 1).equals(" ");
			} catch (BadLocationException e) {
				CorePlugin.getDefault().logError(e.getMessage(), e);
			}
		}

		@Override
		public boolean visit(ClassInstanceCreation node) {
			return visitNode(node, Suppliers.memoize(node::arguments),
					method -> Arrays.asList(method.getParameterTypes()),
					Suppliers.memoize(node::resolveConstructorBinding));
		}

		@Override
		public boolean visit(MethodInvocation node) {
			return visitNode(node, Suppliers.memoize(node::arguments),
					method -> Arrays.asList(method.getParameterTypes()), Suppliers.memoize(node::resolveMethodBinding));
		}

		private boolean visitNode(ASTNode node, Supplier<List<ASTNode>> argumentSupplier,
				Function<IMethodBinding, List<ITypeBinding>> parameterSupplier,
				Supplier<IMethodBinding> bindingSupplier) {
			final CodeRange current = new CodeRange(node.getStartPosition(), node.getStartPosition() + node.getLength(),
					node);

			// for varargs they are considered as single parameter methods with a array.
			int varArgsOffset = preceedSpace ? 2 : 1;
			final int adjustedOffset = bindingSupplier.get().isVarargs() ? offset - varArgsOffset : offset;
			if (current.inRange(adjustedOffset) && (lastVisited == null || lastVisited.inRange(current))) {
				ITypeBinding typeAtOffset = findParameterTypeAtOffset(argumentSupplier, parameterSupplier,
						bindingSupplier);
				try {
					if (typeAtOffset != null) {
						expectedType = project.findType(Signature.getTypeErasure(typeAtOffset.getQualifiedName()));
					}
				} catch (JavaModelException e) {
					CorePlugin.getDefault().logError(e.getMessage(), e);
				}
				lastVisited = current;
				return true;
			}
			return false;
		}

		private ITypeBinding findParameterTypeAtOffset(Supplier<List<ASTNode>> argumentSupplier,
				Function<IMethodBinding, List<ITypeBinding>> parameterSupplier,
				Supplier<IMethodBinding> bindingSupplier) {
			final List<ASTNode> arguments = argumentSupplier.get();

			IMethodBinding binding = bindingSupplier.get();
			if (binding != null) {
				List<ITypeBinding> parameters = parameterSupplier.apply(binding);
				if (!parameters.isEmpty()) {
					if (binding.isVarargs()) {
						return parameters.get(0).getElementType();
					} else {
						return parameters.get(0);
					}
				}
			}

			if (arguments.isEmpty() && (binding != null)) {
				List<ITypeBinding> parameters = parameterSupplier.apply(binding);
				if (!parameters.isEmpty()) {
					if (binding.isVarargs()) {
						return parameters.get(0).getElementType();
					} else {
						return parameters.get(0);
					}
				}
			}

			int typeIndex = -1;
			int checkOffset = preceedSpace ? offset - 1 : offset;

			for (int i = 0; i < arguments.size(); i++) {
				final ASTNode astNode = arguments.get(i);
				if (astNode.getStartPosition() <= checkOffset
						&& (astNode.getStartPosition() + astNode.getLength()) >= checkOffset) {
					if (!(astNode instanceof ClassInstanceCreation || astNode instanceof MethodInvocation)) {
						typeIndex = i;
					}
					break;
				}
			}

			if ((typeIndex > -1) && (binding != null)) {
				return parameterSupplier.apply(binding).get(typeIndex);
			}
			return null;
		}

		public IType getExpectedType() {
			return expectedType;
		}
	}
}