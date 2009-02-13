package com.intellij.psi.impl.source.text;

import com.intellij.lang.ASTFactory;
import com.intellij.lang.ASTNode;
import com.intellij.lang.Language;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.ex.DocumentBulkUpdateListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.pom.PomManager;
import com.intellij.pom.PomModel;
import com.intellij.pom.event.PomModelEvent;
import com.intellij.pom.impl.PomTransactionBase;
import com.intellij.pom.tree.TreeAspect;
import com.intellij.pom.tree.TreeAspectEvent;
import com.intellij.psi.*;
import com.intellij.psi.impl.PsiManagerEx;
import com.intellij.psi.impl.PsiManagerImpl;
import com.intellij.psi.impl.PsiTreeChangeEventImpl;
import com.intellij.psi.impl.source.CodeFragmentElement;
import com.intellij.psi.impl.source.DummyHolder;
import com.intellij.psi.impl.source.PsiFileImpl;
import com.intellij.psi.impl.source.parsing.ChameleonTransforming;
import com.intellij.psi.impl.source.tree.*;
import com.intellij.psi.templateLanguages.ITemplateDataElementType;
import com.intellij.psi.text.BlockSupport;
import com.intellij.psi.tree.IChameleonElementType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.IErrorCounterChameleonElementType;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.util.CharTable;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.diff.DiffTree;

public class BlockSupportImpl extends BlockSupport {
  private static final Logger LOG = Logger.getInstance("#com.intellij.psi.impl.source.text.BlockSupportImpl");

  public BlockSupportImpl(Project project) {
    project.getMessageBus().connect().subscribe(DocumentBulkUpdateListener.TOPIC, new DocumentBulkUpdateListener.Adapter() {
      public void updateStarted(final Document doc) {
        doc.putUserData(DO_NOT_REPARSE_INCREMENTALLY,  Boolean.TRUE);
      }
    });
  }

  public void reparseRange(PsiFile file, int startOffset, int endOffset, CharSequence newTextS) throws IncorrectOperationException {
    LOG.assertTrue(file.isValid());
    final PsiFileImpl psiFile = (PsiFileImpl)file;
    final Document document = psiFile.getViewProvider().getDocument();
    assert document != null;
    document.replaceString(startOffset, endOffset, newTextS);
    PsiDocumentManager.getInstance(psiFile.getProject()).commitDocument(document);

    //final CompositeElement element = psiFile.calcTreeElement();
    //char[] newText = newTextS.toCharArray();
    //int fileLength = element.getTextLength();
    //int lengthShift = newText.length - (endOffset - startOffset);
    //
    //final PsiFileImpl fileImpl = (PsiFileImpl)file;
    //final char[] newFileText = lengthShift > 0 ? new char[fileLength + lengthShift] : new char[fileLength];
    //SourceUtil.toBuffer(fileImpl.getTreeElement(), newFileText, 0);
    //
    //System.arraycopy(newFileText, endOffset, newFileText, endOffset + lengthShift, fileLength - endOffset);
    //System.arraycopy(newText, 0, newFileText, startOffset, newText.length);
    //
    //if(startOffset > 0) startOffset--;
    //reparseRangeInternal(file, startOffset, endOffset, lengthShift, newFileText);
  }


  public void reparseRange(final PsiFile file,
                           final int startOffset,
                           final int endOffset,
                           final int lengthShift,
                           final CharSequence newFileText) {
    // adjust editor offsets to damage area markers
    file.getManager().performActionWithFormatterDisabled(new Runnable() {
      public void run() {
        reparseRangeInternal(file, startOffset > 0 ? startOffset - 1 : 0, endOffset, lengthShift, newFileText);
      }
    });
  }

  private static void reparseRangeInternal(PsiFile file, int startOffset, int endOffset, int lengthShift, CharSequence newFileText) {
    file.getViewProvider().beforeContentsSynchronized();
    final PsiFileImpl fileImpl = (PsiFileImpl)file;
    Project project = fileImpl.getProject();
    final CharTable charTable = fileImpl.getTreeElement().getCharTable();
    // hack
    final int textLength = file.getTextLength() + lengthShift;

    final FileElement treeFileElement = fileImpl.getTreeElement();

    if (treeFileElement.getElementType() instanceof ITemplateDataElementType ||
        treeFileElement.getFirstChildNode() instanceof ChameleonElement
      ) { // Not able to perform incremental reparse for template data in JSP
      makeFullParse(treeFileElement, newFileText, textLength, fileImpl);
      return;
    }

    final ASTNode leafAtStart = treeFileElement.findLeafElementAt(startOffset);
    final ASTNode leafAtEnd = treeFileElement.findLeafElementAt(endOffset);
    ASTNode parent = leafAtStart != null && leafAtEnd != null ? TreeUtil.findCommonParent(leafAtStart, leafAtEnd) : treeFileElement;
    Language baseLanguage = file.getViewProvider().getBaseLanguage();

    int minErrorLevel = Integer.MAX_VALUE;
    ASTNode bestReparseable = null;
    ASTNode prevReparseable = null;
    boolean theOnlyReparseable = false;

    while (parent != null && !(parent instanceof FileElement)) {
      IElementType elementType = parent.getElementType();
      if (elementType instanceof IChameleonElementType) {
        final TextRange textRange = parent.getTextRange();
        final IChameleonElementType reparseable = (IChameleonElementType)elementType;

        if (reparseable.getLanguage() == baseLanguage) {
          boolean languageChanged = false;
          if (prevReparseable != null) {
            languageChanged = prevReparseable.getElementType().getLanguage() != reparseable.getLanguage();
          }

          CharSequence newTextStr = newFileText.subSequence(textRange.getStartOffset(), textRange.getStartOffset() + textRange.getLength() + lengthShift);
          if (reparseable.isParsable(newTextStr, project)) {
            final ChameleonElement chameleon = (ChameleonElement)Factory.createSingleLeafElement(reparseable, newFileText,
                                                                                                 textRange.getStartOffset(),
                                                                                                 textRange.getEndOffset() + lengthShift,
                                                                                                 charTable, file.getManager(), fileImpl);
            TreeElement reparsed = ChameleonElement.reparse(charTable, reparseable, (CompositeElement)parent.getTreeParent(), chameleon);
            mergeTrees(fileImpl, parent, reparsed);
            return;
          }
          if (reparseable instanceof IErrorCounterChameleonElementType) {
            int currentErrorLevel = ((IErrorCounterChameleonElementType)reparseable).getErrorsCount(newTextStr, project);
            if (currentErrorLevel == IErrorCounterChameleonElementType.FATAL_ERROR) {
              prevReparseable = parent;
            }
            else if (Math.abs(currentErrorLevel) < Math.abs(minErrorLevel)) {
              theOnlyReparseable = bestReparseable == null;
              bestReparseable = parent;
              minErrorLevel = currentErrorLevel;
              if (languageChanged) break;
            }
          }
        }

        // invalid content;
      }
      parent = parent.getTreeParent();
    }

    if (bestReparseable != null && !theOnlyReparseable) {
      // best reparseable available
      final TextRange textRange = bestReparseable.getTextRange();
      final ChameleonElement chameleon =
        (ChameleonElement)ASTFactory.leaf(bestReparseable.getElementType(),
                                          treeFileElement.getCharTable().intern(newFileText,
                                                                                textRange.getStartOffset(),
                                                                                textRange.getEndOffset() + lengthShift));
      chameleon.putUserData(CharTable.CHAR_TABLE_KEY, treeFileElement.getCharTable());
      chameleon.setTreeParent((CompositeElement)parent);
      bestReparseable.replaceAllChildrenToChildrenOf(chameleon.transform(treeFileElement.getCharTable()).getTreeParent());
    }
    else {
      //boolean leafChangeOptimized = false;
      //Document document = PsiDocumentManager.getInstance(project).getDocument(fileImpl);
      //if (document != null) {
      //  int changedOffset;
      //  synchronized (document) {
      //    Integer offset = document.getUserData(LexerEditorHighlighter.CHANGED_TOKEN_START_OFFSET);
      //    changedOffset = offset == null ? -1 : offset.intValue();
      //    document.putUserData(LexerEditorHighlighter.CHANGED_TOKEN_START_OFFSET, null);
      //  }
      //  leafChangeOptimized = changedOffset != -1 && optimizeLeafChange(treeFileElement, newFileText, startOffset, endOffset, lengthShift, changedOffset);
      //}
      //if (leafChangeOptimized) {
      //  return;
      //}

      // file reparse

      makeFullParse(parent, newFileText, textLength, fileImpl);
    }
  }

  private static boolean hasErrorElementChild(ASTNode element) {
    if (element == null) return false;
    if (element instanceof PsiErrorElement) return true;
    for (ASTNode child = element.getFirstChildNode(); child != null; child = child.getTreeNext()) {
      if (child instanceof PsiErrorElement) return true;
    }
    return false;
  }

  private static boolean optimizeLeafChange(final FileElement treeFileElement,
                                            final CharSequence newFileText,
                                            int startOffset,
                                            final int endOffset,
                                            final int lengthDiff,
                                            final int changedOffset) {
    final LeafElement leafElement = treeFileElement.findLeafElementAt(startOffset);
    if (leafElement == null || hasErrorElementChild(leafElement.getTreeParent()) || hasErrorElementChild(leafElement.getTreeNext()) ||
        hasErrorElementChild(leafElement.getTreePrev())) {
      return false;
    }
    if (!leafElement.getTextRange().containsRange(startOffset, endOffset)) return false;
    final LeafElement leafElementToChange = treeFileElement.findLeafElementAt(changedOffset);
    if (leafElementToChange == null) return false;
    TextRange leafRangeToChange = leafElementToChange.getTextRange();
    LeafElement newElement = ASTFactory.leaf(leafElementToChange.getElementType(), treeFileElement.getCharTable().intern(
        newFileText, leafRangeToChange.getStartOffset(), leafRangeToChange.getEndOffset() + lengthDiff));
    newElement.putUserData(CharTable.CHAR_TABLE_KEY, treeFileElement.getCharTable());

    leafElementToChange.getTreeParent().replaceChild(leafElementToChange, newElement);
    return true;
  }

  private static void makeFullParse(ASTNode parent, CharSequence newFileText, int textLength, final PsiFileImpl fileImpl) {
    if (parent instanceof CodeFragmentElement) {
      final FileElement holderElement = new DummyHolder(fileImpl.getManager(), null).getTreeElement();
      holderElement.rawAddChildren(fileImpl.createContentLeafElement(holderElement.getCharTable().intern(newFileText, 0, textLength)));
      parent.replaceAllChildrenToChildrenOf(holderElement);
    }
    else {
      final FileViewProvider viewProvider = fileImpl.getViewProvider();
      final LightVirtualFile lightFile = new LightVirtualFile(fileImpl.getName(), viewProvider.getVirtualFile().getFileType(), newFileText, viewProvider.getVirtualFile().getCharset(),
                                                              fileImpl.getModificationStamp());
      final PsiFileImpl newFile = (PsiFileImpl)viewProvider.createCopy(lightFile).getPsi(fileImpl.getLanguage());

      if (newFile == null) {
        LOG.error("View provider " + viewProvider + " refused to parse text with language " + fileImpl.getLanguage());
        return;
      }

      newFile.setOriginalFile(fileImpl);

      final FileElement newFileElement = (FileElement)newFile.getNode();
      final FileElement oldFileElement = (FileElement)fileImpl.getNode();
                                                            
      final Boolean data = fileImpl.getUserData(DO_NOT_REPARSE_INCREMENTALLY);
      if (data != null) fileImpl.putUserData(DO_NOT_REPARSE_INCREMENTALLY, null);

      if (data != null && data.booleanValue()) { // TODO: Just to switch off incremental tree patching for certain conditions (like languages) if necessary.
        replaceFileElementWithEvents(fileImpl, oldFileElement, newFileElement);
      }
      else {
        mergeTrees(fileImpl, oldFileElement, newFileElement);
      }
    }
  }

  private static void replaceFileElementWithEvents(final PsiFileImpl fileImpl, final FileElement fileElement, final FileElement newFileElement) {
    fileImpl.getTreeElement().setCharTable(newFileElement.getCharTable());
    fileElement.replaceAllChildrenToChildrenOf(newFileElement);
  }

  static void replaceFileElement(final PsiFileImpl fileImpl, final FileElement fileElement,
                                         final FileElement newFileElement,
                                         final PsiManagerEx manager) {
    final int oldLength = fileElement.getTextLength();
    sendPsiBeforeEvent(fileImpl);
    if (fileElement.getFirstChildNode() != null) fileElement.rawRemoveAllChildren();
    final ASTNode firstChildNode = newFileElement.getFirstChildNode();
    if (firstChildNode != null) fileElement.rawAddChildren((TreeElement)firstChildNode);
    fileImpl.getTreeElement().setCharTable(newFileElement.getCharTable());
    manager.invalidateFile(fileImpl);
    fileElement.subtreeChanged();
    sendPsiAfterEvent(fileImpl, oldLength);
  }

  private static void mergeTrees(final PsiFileImpl file, final ASTNode oldRoot, final ASTNode newRoot) {
    //System.out.println("---------------------------------------------------");
    synchronized (PsiLock.LOCK) {
      if (newRoot instanceof FileElement) {
        ((FileElement)newRoot).setCharTable(file.getTreeElement().getCharTable());
      }


      final PomModel model = PomManager.getModel(file.getProject());
      try {
        newRoot.putUserData(TREE_TO_BE_REPARSED, oldRoot);

        try {
          ChameleonTransforming.transformChildren(newRoot);
        }
        catch (ReparsedSuccessfullyException e) {
          return; // Successfully merged in PsiBuilderImpl
        }
        
        ChameleonTransforming.transformChildren(oldRoot, true);

        model.runTransaction(new PomTransactionBase(file, model.getModelAspect(TreeAspect.class)) {
          public PomModelEvent runInner() {
            final ASTDiffBuilder builder = new ASTDiffBuilder(file);
            DiffTree.diff(new ASTStructure(oldRoot), new ASTStructure(newRoot), new ASTShallowComparator(), builder);
            file.subtreeChanged();

            return new TreeAspectEvent(model, builder.getEvent());
          }
        });
      }
      catch (IncorrectOperationException e) {
        LOG.error(e);
      }
      catch (Throwable th) {
        LOG.error(th);
      }
      finally {
        ((PsiManagerEx)file.getManager()).invalidateFile(file);
      }
    }
  }

  private static void sendPsiAfterEvent(final PsiFileImpl scope, int oldLength) {
    if (!scope.isPhysical()) return;
    final PsiManagerImpl manager = (PsiManagerImpl)scope.getManager();
    PsiTreeChangeEventImpl event = new PsiTreeChangeEventImpl(manager);
    event.setParent(scope);
    event.setFile(scope);
    event.setOffset(0);
    event.setOldLength(oldLength);
    manager.childrenChanged(event);
  }

  private static void sendPsiBeforeEvent(final PsiFile scope) {
    if (!scope.isPhysical()) return;
    final PsiManagerImpl manager = (PsiManagerImpl)scope.getManager();
    PsiTreeChangeEventImpl event = new PsiTreeChangeEventImpl(manager);
    event.setParent(scope);
    event.setFile(scope);
    event.setOffset(0);
    event.setOldLength(scope.getTextLength());
    manager.beforeChildrenChange(event);
  }
}
