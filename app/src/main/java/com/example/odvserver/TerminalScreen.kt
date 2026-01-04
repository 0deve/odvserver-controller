package com.example.odvserver

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

data class TerminalItem(
    val path: String,
    val command: String,
    val output: String,
    val isError: Boolean = false
)

@Composable
fun TerminalScreen(
    sshManager: SshManager,
    connectionDetails: ConnectionDetails,
    history: List<TerminalItem>,
    onHistoryUpdate: (List<TerminalItem>) -> Unit,
    modifier: Modifier = Modifier
) {
    // states
    var cmdInput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    // for some reason '~' doesnt work so it has to stay '.'
    var currentPath by remember { mutableStateOf(".") }

    // editor states
    var showEditor by remember { mutableStateOf(false) }
    var editorContent by remember { mutableStateOf("") }
    var editorFilePath by remember { mutableStateOf("") }
    var isSavingFile by remember { mutableStateOf(false) }

    // utils
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current


    //autoscroll
    LaunchedEffect(history.size) {
        if (history.isNotEmpty()) {
            listState.animateScrollToItem(history.size - 1)
        }
    }


    // editor logic
    fun openEditor(path: String, content: String) {
        editorFilePath = path
        editorContent = content
        showEditor = true
    }

    fun saveFile() {
        isSavingFile = true
        scope.launch {
            val result = sshManager.saveFileContent(
                connectionDetails.ip,
                connectionDetails.user,
                connectionDetails.password,
                // if path isnt absolute combine with current
                if (editorFilePath.startsWith("/")) editorFilePath else "$currentPath/$editorFilePath",
                editorContent
            )
            isSavingFile = false
            showEditor = false

            // log
            onHistoryUpdate(history + TerminalItem(currentPath, "save $editorFilePath", result))
        }
    }

    // command logic
    fun executeCommand() {
        if (cmdInput.isBlank() || isLoading) return

        // editor
        if (cmdInput.trim().startsWith("nano") || cmdInput.trim().startsWith("vim") || cmdInput.trim().startsWith("vi")) {
            onHistoryUpdate(history + TerminalItem(currentPath, cmdInput, "Interactive editors not supported.\nUse 'edit <filename>' to open the internal editor.", true))
            cmdInput = ""
            return
        }

        val rawCmd = cmdInput.trim()
        cmdInput = ""
        isLoading = true
        keyboardController?.hide()

        // edit
        if (rawCmd.startsWith("edit ")) {
            val fileName = rawCmd.removePrefix("edit ").trim()
            scope.launch {
                // read with cat
                try {
                    val fullCmd = "cd \"$currentPath\" && cat \"$fileName\""
                    val content = sshManager.sendCommand(
                        connectionDetails.ip,
                        connectionDetails.user,
                        connectionDetails.password,
                        fullCmd
                    )

                    if (content.startsWith("Err")) {
                        onHistoryUpdate(history + TerminalItem(currentPath, rawCmd, content, true))
                    } else {
                        openEditor(fileName, content)
                    }
                } finally {
                    isLoading = false
                }
            }
            return
        }

        scope.launch {
            try{
                val pathSeparator = "___PATH_SEP___"

                // fix for empty cd command hanging
                var finalCmd = rawCmd
                if (finalCmd == "cd") {
                    finalCmd = "cd ~"
                }

                // command
                val chainedCmd = "cd \"$currentPath\" && $finalCmd && echo \"$pathSeparator\" && pwd"

                val fullResult = sshManager.sendCommand(
                    connectionDetails.ip,
                    connectionDetails.user,
                    connectionDetails.password,
                    chainedCmd
                )

                // res
                var displayOutput = fullResult
                var isErr = false

                if (fullResult.contains(pathSeparator)) {
                    val parts = fullResult.split(pathSeparator)
                    if (parts.size >= 2) {
                        displayOutput = parts[0].trim()
                        val ansiRegex = "\\x1b\\[[0-9;]*m".toRegex()
                        // clean path
                        val newPathCandidate = parts[1]
                            .replace(ansiRegex, "")
                            .replace("\n", "")
                            .replace("\r", "")
                            .trim()

                        if (newPathCandidate.isNotBlank()) {
                            currentPath = newPathCandidate
                        }
                    }
                } else {
                    // if err dont change path
                    if (fullResult.startsWith("Err")) isErr = true
                }

                onHistoryUpdate(history + TerminalItem(currentPath, rawCmd, displayOutput, isErr))

            } catch (e: Exception) {
                onHistoryUpdate(history + TerminalItem(currentPath, rawCmd, "Err: ${e.message}", true))
            } finally {
                isLoading = false
            }
        }
    }

    // UI
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            // current path
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${connectionDetails.user}@${connectionDetails.ip}:${currentPath}",
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                    TextButton(
                        onClick = { onHistoryUpdate(emptyList()) }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("CLEAR", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // output list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(history) { item ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // prompt line
                        Text(
                            text = "${connectionDetails.user}@..:[${item.path}] $ ${item.command}",
                            color = Color.Green,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // output
                        if (item.output.isNotBlank()) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = item.output,
                                    color = if (item.isError) Color(0xFFFF5252) else Color(0xFFE0E0E0),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                // copy
                                IconButton(
                                    onClick = { clipboardManager.setText(AnnotatedString(item.output)) },
                                    modifier = Modifier.size(18.dp)
                                ) {
                                    Icon(Icons.Filled.ContentCopy, null, tint = Color.DarkGray)
                                }
                            }
                        }
                    }
                }
            }

            if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            // input
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    val clip = clipboardManager.getText()
                    if (clip != null) cmdInput += clip.text
                }) {
                    Icon(Icons.Filled.ContentPaste, "paste")
                }

                OutlinedTextField(
                    value = cmdInput,
                    onValueChange = { cmdInput = it },
                    placeholder = { Text("Cmd (use 'edit file' for nano)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { executeCommand() })
                )

                Spacer(modifier = Modifier.width(8.dp))

                FilledIconButton(
                    onClick = { executeCommand() },
                    enabled = !isLoading
                ) {
                    Icon(Icons.Filled.Send, "send")
                }
            }
        }

        // editor
        if (showEditor) {
            AlertDialog(
                onDismissRequest = { if(!isSavingFile) showEditor = false },
                modifier = Modifier.fillMaxSize().padding(16.dp),
                title = { Text("Editing: $editorFilePath") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = editorContent,
                            onValueChange = { editorContent = it },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { saveFile() },
                        enabled = !isSavingFile
                    ) {
                        if (isSavingFile) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                        } else {
                            Icon(Icons.Filled.Save, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save & Close")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showEditor = false }) { Text("Cancel") }
                }
            )
        }
    }
}