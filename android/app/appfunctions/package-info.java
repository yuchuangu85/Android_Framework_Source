/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Android App Functions provides APIs for applications to expose functionality to the system for
 * cross-app orchestration, and for system-privileged agents to discover and execute this
 * functionality.
 *
 * <p>Most developers should implement app functions through the <a
 * href="https://developer.android.com/reference/androidx/appfunctions/package-summary">AppFunctions
 * Jetpack SDK</a>. The Jetpack SDK offers a more convenient and type-safe way to build app
 * functions.
 *
 * <p>App Functions is currently a beta/experimental preview feature.
 *
 * <h3>What are App Functions?</h3>
 *
 * <p>An app function is a discrete piece of functionality within an application that is made
 * available for execution by trusted, system-privileged applications (referred to as "agents").
 * This allows agents to orchestrate complex workflows across multiple applications. For example, an
 * agent could execute a "createNote" function in a note-taking app or a "playSong" function in a
 * music app.
 *
 * <p>The App Functions framework interacts with two main players:
 *
 * <ul>
 *   <li><b>The App:</b> Any application that exposes one or more app functions.
 *   <li><b>The Agent:</b> A trusted application that discovers and executes app functions from
 *       other apps.
 * </ul>
 *
 * <p>All interactions with the App Functions system start with {@link AppFunctionManager} and
 * {@link AppFunctionService}.
 *
 * <h3 id="app-perspective">The App: Providing Functions</h3>
 *
 * <p>An app can provide functions in two ways: through a dedicated service or by registering them
 * at runtime. In both cases, functions must first be declared in an XML asset file.
 *
 * <h4>Declaring Function Metadata</h4>
 *
 * <p>All app functions, regardless of implementation, must be declared in an XML asset file. This
 * file defines the {@link AppFunctionMetadata} for each function.
 *
 * <p><b>Example XML declaration (e.g., {@code assets/note_app_functions.xml}):</b>
 *
 * <pre>{@code
 * <appfunctions>
 *     <appfunction>
 *         <id>createNote</id>
 *         <enabledByDefault>true</enabledByDefault>
 *         <parameters>...</parameters>
 *         <returnType>...</returnType>
 *         ...
 *     </appfunction>
 * </appfunctions>
 * }</pre>
 *
 * <h4>Functions implemented using {@link AppFunctionService}</h4>
 *
 * <p>This approach is suitable for functionality that is always available regardless of a
 * component's lifecycle. The system will wake up your app to execute the function when executed by
 * an agent.
 *
 * <ul>
 *   <li>Declare the function metadata in an XML asset file, as described above.
 *   <li>Implement a class that extends {@link AppFunctionService}.
 *   <li>Override the {@link AppFunctionService#onExecuteFunction} method to provide the execution
 *       logic.
 *   <li>Declare the service in your {@code AndroidManifest.xml}, requiring the {@link
 *       android.Manifest.permission#BIND_APP_FUNCTION_SERVICE} permission, and include an intent
 *       filter for the {@link AppFunctionService#SERVICE_INTERFACE} action.
 *   <li>Reference the metadata XML file from the service declaration using a {@code <property>} tag
 *       named {@code "android.app.appfunctions"}.
 * </ul>
 *
 * <p><b>Example {@code AndroidManifest.xml}:</b>
 *
 * <pre>{@code
 * <service
 *     android:name=".NoteAppFunctionService"
 *     android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
 *     android:exported="true">
 *   <property
 *       android:name="android.app.appfunctions"
 *       android:value="note_app_functions.xml" />
 *   <intent-filter>
 *     <action android:name="android.app.appfunctions.AppFunctionService" />
 *   </intent-filter>
 * </service>
 * }</pre>
 *
 * <p><b>Important:</b> Only one {@link AppFunctionService} implementation can be active in an app
 * at a time.
 *
 * <p><b>Example implementation:</b>
 *
 * <pre>
 * class NoteAppFunctionService : AppFunctionService() {
 *     override fun onExecuteFunction(
 *         request: ExecuteAppFunctionRequest,
 *         callingPackage: String,
 *         callingPackageSigningInfo: SigningInfo,
 *         cancellationSignal: CancellationSignal,
 *         callback: OutcomeReceiver&lt;ExecuteAppFunctionResponse, AppFunctionException&gt;
 *     ) {
 *         when (request.functionIdentifier) {
 *             "createNote" -> {
 *                 // Implement createNote functionality.
 *                 callback.onResult(ExecuteAppFunctionResponse(...))
 *             }
 *             else -> {
 *                 // Should never happen, the system will automatically return FUNCTION_NOT_FOUND.
 *                 callback.onError(
 *                     AppFunctionException(
 *                         AppFunctionException.FUNCTION_NOT_FOUND,
 *                         "Unknown function: ${request.functionIdentifier}"
 *                     )
 *                 )
 *             }
 *         }
 *     }
 * }
 * </pre>
 *
 * <p><b>Important:</b> It is strongly recommended that you do not alter your function’s behavior
 * based on the {@code callingPackage} or {@code callingPackageSigningInfo}. Your function should
 * behave consistently for all callers to ensure a predictable experience. Starting in {@link
 * android.os.Build.VERSION_CODES#CINNAMON_BUN}, the value of {@code callingPackage} will always be
 * an empty string and {@code callingPackageSigningInfo} will always be an unknown signing info.
 *
 * <h4>Functions implemented using {@link AppFunctionManager#registerAppFunction}</h4>
 *
 * <p>Starting in Android {@link android.os.Build.VERSION_CODES#CINNAMON_BUN}, this approach is
 * suitable for functionality that is only available when the app is in a certain runtime state,
 * such as functionality tied to a specific {@link android.app.Activity}.
 *
 * <ul>
 *   <li>Declare the function metadata in an XML asset file, as described above. Add a {@code
 *       <scope>activity|global</scope>} tag to each {@code <appfunction>} to define its scope. See
 *       <a href="#function-scopes">Function Scopes</a> below for details.
 *   <li>Implement the {@link AppFunction} interface to define the execution logic.
 *   <li>At runtime (e.g., in {@link android.app.Activity#onStart}), register your {@link
 *       AppFunction} implementation using {@link AppFunctionManager#registerAppFunction}.
 *   <li>Keep the returned {@link AppFunctionRegistration} object and call {@link
 *       AppFunctionRegistration#unregister} when the function is no longer relevant (e.g., in
 *       {@link android.app.Activity#onStop} or before {@link android.app.Service#stopForeground}).
 * </ul>
 *
 * <p><b>Example {@code AndroidManifest.xml}:</b>
 *
 * <pre>{@code
 * <application ...>
 *   <property
 *       android:name="android.app.appfunctions"
 *       android:value="note_runtime_app_functions.xml" />
 *   ...
 * </application>
 * }</pre>
 *
 * <p><b>Example implementation:</b>
 *
 * <pre>
 * class NoteActivity : Activity() {
 *     private lateinit var appFunctionRegistration: AppFunctionRegistration
 *
 *     override fun onStart() {
 *         super.onStart()
 *         val appFunctionManager = getSystemService&lt;AppFunctionManager&gt;()
 *         appFunctionRegistration =
 *             appFunctionManager.registerAppFunction(
 *                 "getActiveNoteContent",
 *                 mainExecutor,
 *                 object : AppFunction {
 *                     override fun onExecuteAppFunction(
 *                         request: ExecuteAppFunctionRequest,
 *                         cancellationSignal: CancellationSignal,
 *                         callback:
 *                             OutcomeReceiver&lt;ExecuteAppFunctionResponse, AppFunctionException&gt;
 *                     ) {
 *                         // Implement getActiveNoteContent functionality.
 *                         callback.onResult(ExecuteAppFunctionResponse(...))
 *                     }
 *                 })
 *     }
 *
 *     override fun onStop() {
 *         appFunctionRegistration.unregister()
 *         super.onStop()
 *     }
 * }
 * </pre>
 *
 * <h4>Function Scopes</h4>
 *
 * <p>Functions implemented using {@link AppFunctionManager#registerAppFunction} can have different
 * scopes, defined in their XML metadata:
 *
 * <ul>
 *   <li>{@link AppFunctionMetadata#SCOPE_ACTIVITY}: The function is tied to a specific {@link
 *       android.app.Activity} instance. Multiple activities can register their own version of the
 *       same function simultaneously. Agents can differentiate between them using the {@link
 *       AppFunctionActivityId}.
 *   <li>{@link AppFunctionMetadata#SCOPE_GLOBAL}: Only one implementation of the function can be
 *       registered at a time for the entire application. This is useful for functions that are tied
 *       to a singleton component, such as a foreground service.
 * </ul>
 *
 * <p>Functions implemented using {@link AppFunctionService} are always considered {@link
 * AppFunctionMetadata#SCOPE_GLOBAL}.
 *
 * <p><b>IMPORTANT:</b> Functions provided with {@link AppFunctionManager#registerAppFunction}
 * called from an {@link android.app.Activity} context should prefer {@link #SCOPE_ACTIVITY}. Only
 * use {@link #SCOPE_GLOBAL} for such functions if you are absolutely sure there can be only one
 * instance of that activity.
 *
 * <h3 id="agent-perspective">The Agent: Discovering and Executing Functions</h3>
 *
 * <p>An agent's lifecycle with an app function typically involves three steps: discovery, state
 * retrieval, and execution.
 *
 * <h4>1. Discovering App Functions</h4>
 *
 * <p>Agents can find available functions using {@link AppFunctionManager#searchAppFunctions}. This
 * method returns the {@link AppFunctionMetadata} for functions that match the given {@link
 * AppFunctionSearchSpec}. This metadata contains essential, non-changing information about a
 * function, such as its unique {@link AppFunctionName} and its execution scope.
 *
 * <h4>2. Retrieving Runtime State</h4>
 *
 * <p>While {@link AppFunctionMetadata} is static, a function's state can change at runtime. For
 * example, a function might be temporarily disabled by the app, or might be registered at runtime
 * from one or more {@link android.app.Activity}s. Agents can query the current {@link
 * AppFunctionState} using {@link AppFunctionManager#getAppFunctionStates}. This state indicates
 * whether the function is currently enabled and, for functions of {@link
 * AppFunctionMetadata#SCOPE_ACTIVITY}, which {@link AppFunctionActivityId} instances have
 * registered it.
 *
 * <p>Agents can also use {@link AppFunctionManager#getAppFunctionActivityStates} to get the list of
 * {@link AppFunctionName}s currently associated with a known {@link AppFunctionActivityId}. This is
 * useful for example for agents coming from a {@link
 * android.service.voice.VoiceInteractionSession}, which they can convert to an {@link
 * AppFunctionActivityId} using {@link
 * android.service.voice.VoiceInteractionSession#getAppFunctionActivityId}.
 *
 * <p>To keep both {@link AppFunctionMetadata} and {@link AppFunctionState} up-to-date during an
 * agentic runtime session, agents can use {@link AppFunctionManager#observeAppFunctions} to be
 * notified of changes to function metadata and state.
 *
 * <h4>3. Executing App Functions</h4>
 *
 * <p>Once an agent has the necessary metadata and confirms the function is enabled, it can execute
 * the function using {@link AppFunctionManager#executeAppFunction}. The agent must construct an
 * {@link ExecuteAppFunctionRequest}, which specifies the target function and any required
 * parameters.
 *
 * <p>The execution can result in a successful {@link ExecuteAppFunctionResponse} or an {@link
 * AppFunctionException} if an error occurs.
 */
package android.app.appfunctions;
