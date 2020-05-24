package magpiebridge.intellij.plugin;

import com.intellij.AppTopics;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.hint.HintManagerImpl;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.colors.ColorKey;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseEventArea;
import com.intellij.openapi.editor.event.EditorMouseMotionListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SimpleTimerTask;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.LightweightHint;
import com.intellij.util.messages.MessageBus;
import com.intellij.util.messages.MessageBusConnection;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.Color;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.Timer;

public class Service {

    private final int flags = HintManager.HIDE_BY_ANY_KEY | HintManager.HIDE_BY_TEXT_CHANGE | HintManager.HIDE_BY_OTHER_HINT | HintManager.HIDE_BY_SCROLLING;

    private final class GutterActions implements EditorGutterAction {
        public GutterActions(List<? extends CodeLens> lenses) {
            this.lenses = new LinkedHashMap<>();
            lenses.forEach(cl -> this.lenses.put(cl.getRange().getStart().getLine(), cl));
        }

        private final Map<Integer, CodeLens> lenses;

        @Override
        public void doAction(int i) {
            Command c = lenses.get(i).getCommand();
            ExecuteCommandParams params = new ExecuteCommandParams();
            params.setCommand(c.getCommand());
            params.setArguments(c.getArguments());
            server.getWorkspaceService().executeCommand(params);
        }

        @Override
        public Cursor getCursor(int i) {
            return Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
        }
    }

    private final class GutterAnnotations implements TextAnnotationGutterProvider {

        public GutterAnnotations(List<? extends CodeLens> lenses) {
            this.lenses = new LinkedHashMap<>();
            lenses.forEach(cl -> this.lenses.put(cl.getRange().getStart().getLine(), cl));
        }

        private final Map<Integer, CodeLens> lenses;

        @Nullable
        @Override
        public String getToolTip(int i, Editor editor) {
            if (lenses.containsKey(i)) {
                StringBuffer msg = new StringBuffer(lenses.get(i).getCommand().getCommand());
                msg.append("(");
                lenses.get(i).getCommand().getArguments().forEach(s -> {
                    msg.append(s.toString()).append(" ");
                });
                msg.append(")");
                return msg.toString();
            } else {
                return null;
            }
        }

        @Nullable
        @Override
        public String getLineText(int i, Editor editor) {
            return lenses.containsKey(i)? lenses.get(i).getCommand().getCommand(): null;
        }

        @Override
        public EditorFontType getStyle(int i, Editor editor) {
            return EditorFontType.BOLD;
        }

        @Nullable
        @Override
        public ColorKey getColor(int i, Editor editor) {
            return ColorKey.createColorKey("LSP", Color.BLUE);
        }

        @Nullable
        @Override
        public Color getBgColor(int i, Editor editor) {
            return editor.getColorsScheme().getDefaultBackground();
        }

        @Override
        public List<AnAction> getPopupActions(int i, Editor editor) {
            return Collections.singletonList(new AnAction() {
                @Override
                public void actionPerformed(@NotNull AnActionEvent anActionEvent) {
                    Command c = lenses.get(i).getCommand();
                    ExecuteCommandParams params = new ExecuteCommandParams();
                    params.setCommand(c.getCommand());
                    params.setArguments(c.getArguments());
                    server.getWorkspaceService().executeCommand(params);
                 }
            });
        }

        @Override
        public void gutterClosed() {

        }
    };

    private final LanguageServer server;

    private final Project project;
    private  QuickFixes codeActions;

    public Service(Project project, LanguageServer server, LanguageClient lc) {
        this.project = project;
        this.server = server;
        this.codeActions = project.getComponent(QuickFixes.class);

        if (server instanceof LanguageClientAware) {
            ((LanguageClientAware)server).connect(lc);
        }

        String rootPath = project.getBasePath();
        InitializeParams init = new InitializeParams();
        init.setRootUri(Util.fixUrl(rootPath.startsWith("/")? "file:" + rootPath: "file:///" + rootPath));
        init.setTrace("verbose");
        server.initialize(init).thenAccept(ir -> {
            assert ir.getCapabilities().getCodeActionProvider().getLeft();

            InitializedParams ip = new InitializedParams();
            server.initialized(ip);

            MessageBus bus = project.getMessageBus();
            MessageBusConnection busStop = bus.connect();

            busStop.subscribe(AppTopics.FILE_DOCUMENT_SYNC, new FileDocumentManagerListener() {
                @Override
                public void beforeDocumentSaving(@NotNull Document document) {
                        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
                        DidSaveTextDocumentParams params = new DidSaveTextDocumentParams();
                        TextDocumentIdentifier doc = new TextDocumentIdentifier();
                        doc.setUri(file.getUrl());
                        params.setText(document.getText());
                        params.setTextDocument(doc);
                        server.getTextDocumentService().didSave(params);
                  }

                @Override
                public void fileContentReloaded(@NotNull VirtualFile file, @NotNull Document document) {

                }

                @Override
                public void fileContentLoaded(@NotNull VirtualFile file, @NotNull Document document) {

                }
            });

            busStop.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {

                private void open(@NotNull VirtualFile file) {
                    Document intelliJDoc = FileDocumentManager.getInstance().getDocument(file);
                    assert intelliJDoc != null;

                    DidOpenTextDocumentParams params = new DidOpenTextDocumentParams();
                        TextDocumentItem doc = new TextDocumentItem();

                        doc.setUri(Util.fixUrl(file.getUrl()));

                        doc.setLanguageId(file.getExtension());
                        doc.setText(intelliJDoc.getText());
                        params.setTextDocument(doc);

                        server.getTextDocumentService().didOpen(params);

                        CodeLensParams clp = new CodeLensParams();
                        TextDocumentIdentifier tdi = new TextDocumentIdentifier();
                        tdi.setUri(Util.fixUrl(file.getUrl()));
                        clp.setTextDocument(tdi);
                        server.getTextDocumentService().codeLens(clp).thenAccept(cls -> {
                            ApplicationManager.getApplication().runReadAction(() -> {
                                GutterAnnotations gutter = new GutterAnnotations(cls);
                                GutterActions actions = new GutterActions(cls);
                                for (Editor e : EditorFactory.getInstance().getEditors(intelliJDoc, project)) {
                                    e.getGutter().registerTextAnnotation(gutter, actions);
                                }
                            });
                        });

                    /*
                    CodeLensParams clp = new CodeLensParams();
                    server.getTextDocumentService().codeLens(clp).thenAccept(cls -> {
                        cls.forEach(cl -> {
                            Range clr = cl.getRange();
                            Position clpos = clr.getEnd();
                            for (Editor e : EditorFactory.getInstance().getEditors(intelliJDoc, project)) {
                                 e.getInlayModel().addInlineElement(
                                        intelliJDoc.getLineStartOffset(clpos.getLine()) + clpos.getCharacter(),
                                        new EditorCustomElementRenderer() {
                                            @Override
                                            public int calcWidthInPixels(@NotNull Inlay inlay) {
                                                return 35;
                                            }

                                            @Override
                                            public int calcHeightInPixels(@NotNull Inlay inlay) {
                                                return 20;
                                            }

                                            @Override
                                            public void paint(@NotNull Inlay inlay, @NotNull Graphics g, @NotNull Rectangle targetRegion, @NotNull TextAttributes textAttributes) {
                                                Editor editor = inlay.getEditor();
                                                g.setColor(JBColor.GRAY);
                                                g.drawString(cl.getCommand().getTitle(), targetRegion.x, targetRegion.y + targetRegion.height);
                                            }
                                        });
                            };
                         });
                    });
                    */

                    for (Editor e : EditorFactory.getInstance().getEditors(intelliJDoc, project)) {
                        e.addEditorMouseMotionListener(new EditorMouseMotionListener() {
                            @Override
                            public void mouseMoved(@NotNull EditorMouseEvent ev) {
                                if (ev.getArea().equals(EditorMouseEventArea.EDITING_AREA)) {
                                    TextDocumentPositionParams pos = new TextDocumentPositionParams();
                                    Position mp = new Position();
                                    int offset = e.logicalPositionToOffset(e.xyToLogicalPosition(ev.getMouseEvent().getPoint()));
                                    LogicalPosition logicalPos = e.offsetToLogicalPosition(offset);
                                    int line = intelliJDoc.getLineNumber(offset);
                                    int col = offset - intelliJDoc.getLineStartOffset(line);
                                    mp.setLine(line);
                                    mp.setCharacter(col);
                                    pos.setPosition(mp);
                                    TextDocumentIdentifier id = new TextDocumentIdentifier();
                                    String uri= Util.fixUrl(file.getUrl());
                                    id.setUri(uri);
                                    pos.setTextDocument(id);
                                    CodeActionParams codeActionParams = new CodeActionParams();
                                    Range range =new Range(mp, mp);
                                    codeActionParams.setRange(range);
                                    codeActionParams.setTextDocument(id);
                                    codeActionParams.setContext(new CodeActionContext(new ArrayList<Diagnostic>()));
                                    server.getTextDocumentService().codeAction(codeActionParams).thenAccept(actions->{
                                        if(actions.size()>=0) {
                                            codeActions.addCodeActions(Util.getDocument(file), range, server, actions);
                                        }
                                    });


                                    server.getTextDocumentService().hover(pos).thenAccept(h -> {
                                        if (h != null) {
                                            String text = "";
                                            if (h.getContents().isLeft()) {
                                                for (Either<String, MarkedString> str : h.getContents().getLeft()) {
                                                    if (str.isRight()) {
                                                        MarkedString ms = str.getRight();
                                                        text += ms;
                                                    } else {
                                                        text += str.getLeft();
                                                    }
                                                }
                                            } else {
                                                MarkupContent mc = h.getContents().getRight();
                                                text += mc.getValue();
                                            }
                                            LightweightHint hint = new LightweightHint(new JLabel(text));
                                            Point p = HintManagerImpl.getHintPosition(hint, e, logicalPos, HintManager.ABOVE);
                                            HintManagerImpl.getInstanceImpl().showEditorHint(hint, e, p, flags, -1, true,
                                                    HintManagerImpl.createHintHint(e, p, hint,
                                                            HintManager.ABOVE).setContentActive(true));
                                        }
                                    });
                                }
                            }
                        });
                    }

                    if (lc instanceof magpiebridge.intellij.client.LanguageClient) {
                        ((magpiebridge.intellij.client.LanguageClient) lc).showDiagnostics(file);
                    }
                }

                @Override
                public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
                    open(file);
                }

                @Override
                public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
                    DidCloseTextDocumentParams params = new DidCloseTextDocumentParams();
                    TextDocumentIdentifier doc = new TextDocumentIdentifier();
                    doc.setUri(Util.fixUrl(file.getUrl()));
                    params.setTextDocument(doc);
                    server.getTextDocumentService().didClose(params);

                }
            });
        }

        );
    }

    public void shutDown(Runnable andThen){
        Timer timer = new Timer();
        timer.schedule(new TimerTask(){
            @Override
            public void run() {
                andThen.run();
            }
        }, 3000); // run andThen after timeout of 3 seconds, if server does not respond to shutdown
        server.shutdown().thenRunAsync(()->{
            timer.cancel();
            andThen.run();
        });
    }

    public void exit() {
        server.exit();
    }

}
