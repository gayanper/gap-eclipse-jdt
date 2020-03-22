package org.gap.eclipse.jdt.types;

import java.util.Set;

import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.ui.text.java.ContentAssistInvocationContext;
import org.eclipse.jdt.ui.text.java.JavaContentAssistInvocationContext;
import org.eclipse.jface.text.BadLocationException;
import org.gap.eclipse.jdt.CorePlugin;

import com.google.common.collect.Sets;

public class AbstractSmartProposalComputer {

	private Set<String> unsupportedTypes = Sets.newHashSet("java.lang.String", "java.lang.Object",
			"java.lang.Cloneable", "java.lang.Throwable", "java.lang.Exception");

	public AbstractSmartProposalComputer() {
		super();
	}

	protected final CompletionProposal createImportProposal(JavaContentAssistInvocationContext context, IType type)
			throws JavaModelException {
		CompletionProposal proposal = CompletionProposal.create(CompletionProposal.TYPE_IMPORT,
				context.getInvocationOffset());
		String fullyQualifiedName = type.getFullyQualifiedName();
		proposal.setCompletion(fullyQualifiedName.toCharArray());
		proposal.setDeclarationSignature(type.getPackageFragment().getElementName().toCharArray());
		proposal.setFlags(type.getFlags());
		proposal.setSignature(Signature.createTypeSignature(fullyQualifiedName, true).toCharArray());

		return proposal;
	}
	
	protected final boolean shouldCompute(ContentAssistInvocationContext context) {
		try {
			return !".".equals(context.getDocument().get(context.getInvocationOffset() - 1, 1));
		} catch (BadLocationException e) {
			CorePlugin.getDefault().logError(e.getMessage(), e);
			return false;
		}
	}
	
	protected final boolean isUnsupportedType(String fqn) {
		return unsupportedTypes.contains(fqn);
	}
}
