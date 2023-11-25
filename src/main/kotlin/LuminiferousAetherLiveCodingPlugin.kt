package com.xemantic.aether.plugin

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.xemantic.osc.OscInput
import com.xemantic.osc.convert.oscEncoders
import com.xemantic.osc.route
import com.xemantic.osc.udp.UdpOscTransport
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

class LuminiferousAetherLiveCoding {

  private var editor: Editor? = null

  private var broadcaster: OscCodeBroadcaster? = null

  val started: Boolean get() = editor != null

  private val caretListener = object : CaretListener {
    override fun caretPositionChanged(event: CaretEvent) {
      broadcaster?.sendPosition(event.newPosition)
    }
  }

  private inner class LiveDocumentListener(
    private val editor: Editor
  ) : DocumentListener {
    override fun documentChanged(event: DocumentEvent) {
      broadcaster?.sendCode(event.document.text)
      broadcaster?.sendPosition(editor.caretModel.logicalPosition)
    }
  }

  private var documentListener: DocumentListener? = null

  fun start(
    editor: Editor,
    connectionEditor: OscConnectionEditor
  ) {
    broadcaster = with(connectionEditor) {
      OscCodeBroadcaster(
        localPortField.text.toInt(),
        remoteHostField.text,
        remotePortField.text.toInt(),
        codeAddressField.text,
        caretAddressField.text,
        remoteCodeSubscriptionAddress.text,
        remoteCodeUnsubscriptionAddress.text,
        localCodeAddress.text
      ) { code ->
        WriteCommandAction.runWriteCommandAction(editor.project) {
          editor.document.setText(code)
        }
      }
    }
    editor.apply {
      caretModel.addCaretListener(caretListener)
      documentListener = LiveDocumentListener(editor)
      document.addDocumentListener(documentListener!!)
    }
    this.editor = editor
  }

  fun stop() {
    editor?.run {
      caretModel.removeCaretListener(caretListener)
      document.removeDocumentListener(documentListener!!)
    }
    broadcaster?.run {
      close()
    }
    editor = null
    broadcaster = null
  }

}

private class OscCodeBroadcaster(
  localPort: Int,
  remoteHost: String,
  remotePort: Int,
  private val codeAddress: String,
  private val caretAddress: String,
  private val remoteCodeSubscriptionAddress: String,
  private val remoteCodeUnsubcriptionAddress: String,
  localCodeAddress: String,
  onExternalCodeUpdate: (code: String) -> Unit
) : AutoCloseable {

  private val scope = CoroutineScope(
    newSingleThreadContext("editor-osc")
  )

  private val selectorManager = SelectorManager()

  private val socket = aSocket(selectorManager).udp().bind(
    InetSocketAddress("::", localPort)
  )

  private val oscTransport = UdpOscTransport(socket)

  private val input = OscInput(scope) {
    connect(oscTransport)
  }

  private val codeInputRoute = input.route<String>(localCodeAddress) {
    onExternalCodeUpdate(it)
  }

  init {
    scope.launch {
      codeInputRoute.messages
        .map { it.value }
        .collect { code ->
          onExternalCodeUpdate(code)
        }
    }
  }
  private val output = oscTransport.output(remoteHost, remotePort) {
    encoders += oscEncoders {
      encoder<LogicalPosition> {position ->
        typeTag("ii")
        int(position.line)
        int(position.column)
      }
    }
    route<String>(codeAddress)
    route<LogicalPosition>(caretAddress)
    route<String>(remoteCodeSubscriptionAddress)
    route<String>(remoteCodeUnsubcriptionAddress)
  }

  init {
    scope.launch {
      output.send(remoteCodeSubscriptionAddress, codeAddress)
    }
  }

  fun sendPosition(position: LogicalPosition) {
    scope.launch {
      output.send(caretAddress, position)
    }
  }

  fun sendCode(code: String) {
    scope.launch {
      output.send(codeAddress, code)
    }
  }

  override fun close() {
    runBlocking {
      scope.launch {
        output.send(
          remoteCodeSubscriptionAddress,
          codeAddress
        )
      }
    }
    output.close()
    socket.close()
    selectorManager.close()
    scope.cancel()
  }

}

class LuminiferousAetherToolWindowFactory : ToolWindowFactory, DumbAware {

  override fun createToolWindowContent(
    project: Project,
    toolWindow: ToolWindow
  ) {
    val aether = LuminiferousAetherLiveCoding()

    val connectionEditor = OscConnectionEditor().apply {
      connectAction.addActionListener {
        if (aether.started) {
          aether.stop()
          connectAction.text = "Connect"
        } else {
          messageLabel.text = ""
          val editor = FileEditorManager.getInstance(project).selectedTextEditor
          if (editor == null) {
            messageLabel.text = "no editor selected"
          } else {
            try {
              aether.start(
                editor,
                connectionEditor = this
              )
              connectAction.text =  "Disconnect"
            } catch (e : Exception) {
              messageLabel.text = e.message
            }
          }
        }
      }
    }

    val content = ContentFactory.getInstance().createContent(
      connectionEditor.getContentPane(),
      "",
      false
    )

    toolWindow.contentManager.addContent(content)
  }

}

class OscConnectionEditor  {

  val localPortField = JTextField("43001")
  val remoteHostField = JTextField("localhost")
  val remotePortField = JTextField("42001")
  val codeAddressField = JTextField("/stimuli/meanings/script")
  val caretAddressField = JTextField("/stimuli/meanings/caret/position")
  val localCodeAddress = JTextField("/code")
  val remoteCodeSubscriptionAddress = JTextField("/code/subscribe")
  val remoteCodeUnsubscriptionAddress = JTextField("/code/unsubscribe")
  val connectAction = JButton("Connect")
  val messageLabel = JLabel("")

  private val panel = JPanel(GridLayout(10, 2)).apply {
    add(JLabel("OSC settings")); add(JLabel(""))
    add(JLabel("Local port")); add(localPortField)
    add(JLabel("Remote host")); add(remoteHostField)
    add(JLabel("Remote port")); add(remotePortField)
    add(JLabel("Code address")); add(codeAddressField)
    add(JLabel("Caret address")); add(caretAddressField)
    add(JLabel("Local code address")); add(localCodeAddress)
    add(JLabel("Remote code subscription address")); add(remoteCodeSubscriptionAddress)
    add(JLabel("Remote code unsubscription address")); add(remoteCodeUnsubscriptionAddress)
    add(connectAction); add(messageLabel)
  }

  fun getContentPane() = panel

}
