/*
 * Copyright (c) 2020 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.systemsearch

import android.content.Intent
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.duckduckgo.app.autocomplete.api.AutoCompleteApi
import com.duckduckgo.app.global.SingleLiveEvent
import com.jakewharton.rxrelay2.PublishRelay
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import java.util.concurrent.TimeUnit

class SystemSearchViewModel(
    private val autoCompleteApi: AutoCompleteApi,
    private val deviceAppsLookup: DeviceAppsLookup
) : ViewModel() {

    data class SystemSearchViewState(
        val queryText: String = "",
        val autocompleteResults: List<AutoCompleteApi.AutoCompleteSuggestion> = emptyList(),
        val appResults: List<DeviceApp> = emptyList()
    )

    sealed class Command {
        data class LaunchBrowser(val query: String) : Command()
        data class LaunchApplication(val intent: Intent) : Command()
    }

    val viewState: MutableLiveData<SystemSearchViewState> = MutableLiveData()
    val command: SingleLiveEvent<Command> = SingleLiveEvent()

    private val autoCompletePublishSubject = PublishRelay.create<String>()
    private var appResults: List<DeviceApp> = emptyList()
    private var autocompleteResults: List<AutoCompleteApi.AutoCompleteSuggestion> = emptyList()

    init {
        resetViewState()
        configureAutoComplete()
    }

    private fun currentViewState(): SystemSearchViewState = viewState.value!!

    fun resetViewState() {
        viewState.value = SystemSearchViewState()
    }

    private fun configureAutoComplete() {
        autoCompletePublishSubject
            .debounce(300, TimeUnit.MILLISECONDS)
            .switchMap { autoCompleteApi.autoComplete(it) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ result ->
                updateAutocompleteResult(result)
            }, { t: Throwable? -> Timber.w(t, "Failed to get search results") })
    }

    fun userUpdatedQuery(query: String) {
        val trimmedQuery = query.trim()
        if (trimmedQuery != currentViewState().queryText) {
            viewState.value = currentViewState().copy(queryText = trimmedQuery)
            updateAppResults(deviceAppsLookup.query(query))
            autoCompletePublishSubject.accept(trimmedQuery)
        }
    }

    private fun updateAppResults(results: List<DeviceApp>) {
        appResults = results
        refreshViewStateResults()
    }

    private fun updateAutocompleteResult(results: AutoCompleteApi.AutoCompleteResult) {
        autocompleteResults = results.suggestions
        refreshViewStateResults()
    }

    private fun refreshViewStateResults() {
        val hasAllResults = autocompleteResults.isNotEmpty() && appResults.isNotEmpty()
        val newSuggestions = if (hasAllResults) autocompleteResults.take(4) else autocompleteResults
        val newApps = if (hasAllResults) appResults.take(4) else appResults
        viewState.value = currentViewState().copy(
            autocompleteResults = newSuggestions,
            appResults = newApps
        )
    }

    fun userClearedQuery() {
        viewState.value = currentViewState().copy(queryText = "", appResults = emptyList())
        autoCompletePublishSubject.accept("")
    }

    fun userSubmittedQuery(query: String) {
        command.value = Command.LaunchBrowser(query)
    }

    fun userSelectedApp(app: DeviceApp) {
        command.value = Command.LaunchApplication(app.launchIntent)
    }
}
