const { onDocumentWritten } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

function orderNumber(order) {
  const number = Number(order.orderNumber || 0);
  return number > 0 ? String(number).padStart(2, "0") : String(order.id || "");
}

async function sendData(token, title, body) {
  if (!token || typeof token !== "string") return;
  try {
    await getMessaging().send({
      token,
      // Data lets AppMessagingService speak the message when Android delivers the
      // high-priority event; the notification payload also lets Android display the
      // written alert if the app process is not currently running.
      notification: {
        title: String(title).slice(0, 100),
        body: String(body).slice(0, 1000)
      },
      data: {
        title: String(title).slice(0, 100),
        body: String(body).slice(0, 1000),
        voice: "true"
      },
      android: {
        priority: "high",
        ttl: 60 * 60 * 1000,
        notification: { channelId: "aguagas_push", priority: "high" }
      }
    });
  } catch (error) {
    console.error("FCM delivery failed:", error.code || error.message);
  }
}

exports.orderNotifications = onDocumentWritten("orders/{orderId}", async (event) => {
  const before = event.data.before.exists ? event.data.before.data() : null;
  const after = event.data.after.exists ? event.data.after.data() : null;
  if (!after || after.demo === true) return;

  const db = getFirestore();
  const order = after;
  const n = orderNumber(order);
  const companyId = String(order.companyId || "");
  const customerToken = String(order.customerFcmToken || "");
  const wasCreated = !before;

  if (wasCreated && companyId) {
    const config = await db.collection("companies").doc(companyId)
      .collection("config").doc("main").get();
    const adminToken = config.exists ? String(config.get("adminFcmToken") || "") : "";
    await sendData(adminToken, "Novo pedido recebido", `Chegou o pedido #${n} de ${order.customer || "um cliente"}.`);
    return;
  }

  if (!before) return;
  if (Number(order.paymentConfirmedAt || 0) > Number(before.paymentConfirmedAt || 0)) {
    await sendData(customerToken, "Pagamento confirmado", `O pagamento do pedido #${n} foi confirmado. Seu pedido foi liberado.`);
  }
  if (Number(order.approvalSentAt || 0) > Number(before.approvalSentAt || 0)) {
    await sendData(customerToken, "Pedido enviado para entrega", `O pedido #${n} foi enviado para entrega.`);
  }
  if (Number(order.deliveryStartedAt || 0) > Number(before.deliveryStartedAt || 0)) {
    await sendData(customerToken, "Entregador a caminho", `O entregador iniciou a entrega do pedido #${n}. Você pode acompanhar a localização no mapa.`);
  }
  if (order.status === "Chegou ao endereço" && before.status !== "Chegou ao endereço") {
    await sendData(customerToken, "Entregador chegou", `O entregador chegou ao endereço do pedido #${n}.`);
  }
  if (order.status === "Cancelado" && before.status !== "Cancelado") {
    await sendData(customerToken, "Pedido cancelado", `O pedido #${n} foi cancelado.`);
  }
});
