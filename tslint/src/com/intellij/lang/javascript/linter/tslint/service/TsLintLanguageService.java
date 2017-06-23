package com.intellij.lang.javascript.linter.tslint.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.intellij.idea.RareLogger;
import com.intellij.lang.javascript.linter.tslint.config.TsLintState;
import com.intellij.lang.javascript.linter.tslint.execution.TsLintConfigFileSearcher;
import com.intellij.lang.javascript.linter.tslint.execution.TsLintOutputJsonParser;
import com.intellij.lang.javascript.linter.tslint.execution.TsLinterError;
import com.intellij.lang.javascript.linter.tslint.service.commands.TsLintFixErrorsCommand;
import com.intellij.lang.javascript.linter.tslint.service.commands.TsLintGetErrorsCommand;
import com.intellij.lang.javascript.linter.tslint.service.protocol.TsLintLanguageServiceProtocol;
import com.intellij.lang.javascript.service.*;
import com.intellij.lang.javascript.service.protocol.JSLanguageServiceAnswer;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.FixedFuture;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.text.SemVer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;


public final class TsLintLanguageService extends JSLanguageServiceBase {
  @NotNull private final static Logger LOG = RareLogger.wrap(Logger.getInstance("#com.intellij.lang.javascript.linter.tslint.service.TsLintLanguageService"), false);
  @NotNull
  private final TsLintConfigFileSearcher myConfigFileSearcher;

  @NotNull
  public static TsLintLanguageService getService(@NotNull Project project) {
    return ServiceManager.getService(project, TsLintLanguageService.class);
  }


  public TsLintLanguageService(@NotNull Project project) {
    super(project);
    myConfigFileSearcher = new TsLintConfigFileSearcher();
  }

  @NotNull
  @Override
  protected String getProcessName() {
    return "TSLint";
  }

  public final Future<List<TsLinterError>> highlight(@Nullable VirtualFile virtualFile,
                                                     @Nullable VirtualFile config,
                                                     @Nullable String content) {
    final JSLanguageServiceQueue process = getProcess();
    if (process == null) {
      return new FixedFuture<>(Collections.singletonList(new TsLinterError(JSLanguageServiceUtil.getLanguageServiceCreationError(this))));
    }
    final MyParameters parameters = MyParameters.checkParameters(virtualFile, config, process);
    if (parameters.getErrors() != null) return new FixedFuture<>(parameters.getErrors());
    TsLintGetErrorsCommand command = new TsLintGetErrorsCommand(parameters.getPath(), parameters.getConfigPath(),
                                                                StringUtil.notNullize(content));
    return process.execute(command, createHighlightProcessor(parameters.getPath()));
  }

  public final Future<List<TsLinterError>> highlightAndFix(@Nullable VirtualFile virtualFile, @NotNull TsLintState state) {
    final JSLanguageServiceQueue process = getProcess();
    if (process == null) {
      return new FixedFuture<>(Collections.singletonList(new TsLinterError(JSLanguageServiceUtil.getLanguageServiceCreationError(this))));
    }

    VirtualFile config = virtualFile == null ? null : myConfigFileSearcher.getConfig(state, virtualFile);
    final MyParameters parameters = MyParameters.checkParameters(virtualFile, config, process);
    if (parameters.getErrors() != null) return new FixedFuture<>(parameters.getErrors());

    //doesn't pass content (file should be saved before)
    TsLintFixErrorsCommand command = new TsLintFixErrorsCommand(parameters.getPath(), parameters.getConfigPath());
    return process.execute(command, createHighlightProcessor(parameters.getPath()));
  }

  private static class MyParameters {
    @NotNull private final String myConfigPath;
    @NotNull private final String myPath;
    @Nullable private final List<TsLinterError> myErrors;

    private MyParameters(@NotNull String path, @NotNull String configPath,
                         @Nullable List<TsLinterError> errors) {
      myConfigPath = configPath;
      myPath = path;
      myErrors = errors;
    }

    public static MyParameters checkParameters(@Nullable VirtualFile virtualFile, @Nullable VirtualFile config,
                                               @Nullable JSLanguageServiceQueue process) {
      String error;
      if (process == null) {
        error = "Can not create language service";
      } else if (virtualFile == null || !virtualFile.isInLocalFileSystem()) {
        error = "Path not specified";
      } else if (config == null) {
        error = "Config file was not found.";
      } else {
        final String configPath = JSLanguageServiceUtil.normalizeNameAndPath(config);
        final String path = JSLanguageServiceUtil.normalizeNameAndPath(virtualFile);
        if (configPath != null && path != null) {
          return new MyParameters(path, configPath, null);
        }
        error = "Can not work with the path: " + (path != null ? path : configPath);
      }
      return new MyParameters("", "", Collections.singletonList(new TsLinterError(error)));
    }

    @NotNull
    public String getConfigPath() {
      return myConfigPath;
    }

    @NotNull
    public String getPath() {
      return myPath;
    }

    @Nullable
    public List<TsLinterError> getErrors() {
      return myErrors;
    }
  }

  @NotNull
  private static JSLanguageServiceCommandProcessor<List<TsLinterError>> createHighlightProcessor(@NotNull String path) {
    return (object, answer) -> parseResults(answer, path);
  }


  @Nullable
  private static List<TsLinterError> parseResults(@NotNull JSLanguageServiceAnswer answer, @NotNull String path) {
    final JsonObject element = answer.getElement();
    final JsonElement error = element.get("error");
    if (error != null) {
      return Collections.singletonList(new TsLinterError(error.getAsString()));
    }
    final JsonElement body = parseBody(element);
    if (body == null) return null;
    final String version = element.get("version").getAsString();
    final SemVer tsLintVersion = SemVer.parseFromText(version);
    final boolean isZeroBased = TsLintOutputJsonParser.isVersionZeroBased(tsLintVersion);
    final TsLintOutputJsonParser parser = new TsLintOutputJsonParser(path, body, isZeroBased);
    return ContainerUtil.newArrayList(parser.getErrors());
  }

  private static JsonElement parseBody(@NotNull JsonObject element) {
    final JsonElement body = element.get("body");
    if (body == null) {
      //we do not currently treat empty body as error in protocol
      return null;
    } else {
      if (body.isJsonPrimitive() && body.getAsJsonPrimitive().isString()) {
        final String bodyContent = StringUtil.unquoteString(body.getAsJsonPrimitive().getAsString());
        if (!StringUtil.isEmptyOrSpaces(bodyContent)) {
          try {
            return new JsonParser().parse(bodyContent);
          } catch (JsonParseException e) {
            LOG.info(String.format("Problem parsing body: '%s'\n%s", body, e.getMessage()), e);
          }
        }
      } else {
        LOG.info(String.format("Error body type, should be a string with json inside. Body:'%s'", body.getAsString()));
      }
    }
    return null;
  }

  @Override
  protected final JSLanguageServiceQueue createLanguageServiceQueue() {
    TsLintLanguageServiceProtocol protocol = new TsLintLanguageServiceProtocol(myProject, (el) -> {
    });

    return new JSLanguageServiceQueueImpl(myProject, protocol, myProcessConnector, myDefaultReporter,
                                          new JSLanguageServiceDefaultCacheData());
  }

  @Override
  protected final boolean needInitToolWindow() {
    return false;
  }
}
