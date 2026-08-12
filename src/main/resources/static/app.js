(() => {
    "use strict";

    const state = {
        jobId: null,
        type: null,
        value: null,
        file: null,
        pdfUrl: null,
        dirty: false,
        pollingToken: 0
    };

    const $ = (selector) => document.querySelector(selector);
    const uploadView = $("#upload-view");
    const processingView = $("#processing-view");
    const reviewView = $("#review-view");
    const uploadForm = $("#upload-form");
    const fileInput = $("#pdf-file");
    const dropZone = $("#drop-zone");
    const fileLabel = $("#file-label");
    const uploadError = $("#upload-error");
    const reviewError = $("#review-error");
    const saveButton = $("#save-button");
    const downloadButton = $("#download-button");
    const saveState = $("#save-state");
    const editor = $("#editor");

    function showOnly(view) {
        [uploadView, processingView, reviewView].forEach(candidate => candidate.hidden = candidate !== view);
    }

    function showError(target, message) {
        target.textContent = message;
        target.hidden = false;
    }

    function clearError(target) {
        target.textContent = "";
        target.hidden = true;
    }

    function setFile(file) {
        state.file = file || null;
        fileLabel.textContent = file ? file.name : "ou arraste o arquivo até aqui";
    }

    fileInput.addEventListener("change", () => setFile(fileInput.files[0]));
    ["dragenter", "dragover"].forEach(eventName => dropZone.addEventListener(eventName, event => {
        event.preventDefault();
        dropZone.classList.add("dragging");
    }));
    ["dragleave", "drop"].forEach(eventName => dropZone.addEventListener(eventName, event => {
        event.preventDefault();
        dropZone.classList.remove("dragging");
    }));
    dropZone.addEventListener("drop", event => {
        const file = event.dataTransfer.files[0];
        if (file) setFile(file);
    });

    uploadForm.addEventListener("submit", async event => {
        event.preventDefault();
        clearError(uploadError);
        const file = state.file || fileInput.files[0];
        if (!file) return showError(uploadError, "Escolha um arquivo PDF.");
        if (!file.name.toLowerCase().endsWith(".pdf") || (file.type && file.type !== "application/pdf")) {
            return showError(uploadError, "O arquivo deve ser um PDF válido.");
        }

        state.type = $("#document-type").value;
        const form = new FormData();
        form.append("arquivo", file);
        form.append("tipo", state.type);
        const submit = $("#submit-upload");
        submit.disabled = true;
        showOnly(processingView);
        try {
            const response = await fetch("/api/transcricoes", { method: "POST", body: form });
            const body = await readResponse(response);
            if (response.status !== 202) throw new Error(body.erro || "O servidor não aceitou o arquivo.");
            state.jobId = body.id;
            state.pdfUrl = URL.createObjectURL(file);
            await pollJob(++state.pollingToken);
        } catch (error) {
            showOnly(uploadView);
            showError(uploadError, error.message || "Falha ao enviar o documento.");
        } finally {
            submit.disabled = false;
        }
    });

    async function pollJob(token) {
        let attempts = 0;
        while (token === state.pollingToken) {
            const response = await fetch(`/api/transcricoes/${encodeURIComponent(state.jobId)}`, { cache: "no-store" });
            const job = await readResponse(response);
            if (!response.ok) throw new Error(job.erro || "Não foi possível acompanhar o processamento.");
            if (job.status === "concluido") {
                state.value = job.value;
                state.type = job.tipo;
                openReview();
                return;
            }
            if (job.status === "erro") throw new Error(job.erro || "O documento não pôde ser processado.");
            attempts++;
            $("#processing-message").textContent = attempts < 4
                ? "Verificando texto do PDF e aplicando OCR quando necessário."
                : "O OCR pode levar um pouco mais em documentos escaneados.";
            await wait(Math.min(750 + attempts * 80, 1600));
        }
    }

    function openReview() {
        $("#review-title").textContent = state.type === "cartao-ponto"
            ? "Revise o cartão de ponto" : "Revise os holerites";
        const viewer = $("#pdf-viewer");
        viewer.data = state.pdfUrl;
        $("#pdf-fallback").href = state.pdfUrl;
        setDirty(false);
        clearError(reviewError);
        renderEditor();
        showOnly(reviewView);
    }

    function setDirty(dirty = true) {
        state.dirty = dirty;
        saveButton.disabled = !dirty;
        saveState.textContent = dirty ? "Alterações ainda não salvas" : "Sem alterações pendentes";
        saveState.classList.toggle("dirty", dirty);
    }

    function renderEditor() {
        editor.replaceChildren();
        if (state.type === "cartao-ponto") renderTimecard();
        else renderPayslip();
    }

    function renderTimecard() {
        const warnings = timecardWarnings(state.value);
        const days = state.value.pages.flatMap(page => page.days);
        const maxPunches = Math.max(0, ...days.map(day => day.punches.length));
        const pairs = Math.ceil(maxPunches / 2);
        const wrap = make("div", "table-wrap");
        const table = make("table", "data-table");
        const head = table.createTHead().insertRow();
        ["Data", ...Array.from({ length: pairs * 2 }, (_, index) => `${index % 2 ? "Saída" : "Entrada"} ${Math.floor(index / 2) + 1}`), "Ações"]
            .forEach(label => makeHeader(head, label));
        const body = table.createTBody();
        let warningIndex = 0;
        state.value.pages.forEach(page => page.days.forEach(day => {
            const warning = warnings[warningIndex++];
            const row = body.insertRow();
            decorateWarning(row, warning);
            const dateCell = row.insertCell();
            dateCell.append(input(day.date_raw, value => { day.date_raw = value; setDirty(); }, "Data impressa"));
            warningList(dateCell, warning);

            for (let index = 0; index < pairs * 2; index++) {
                const cell = row.insertCell();
                const punch = day.punches[index];
                if (!punch) {
                    cell.textContent = "—";
                    continue;
                }
                cell.append(input(punch.time_hhmm, value => { punch.time_hhmm = value; setDirty(); }, "Horário normalizado"));
                const raw = input(punch.time_raw, value => { punch.time_raw = value; setDirty(); }, "Leitura original", "data-input raw-input");
                cell.append(raw, caption("raw"));
            }
            const actions = row.insertCell();
            actions.className = "actions-cell";
            const actionsRow = make("div", "row-actions");
            actionsRow.append(miniButton("+ batida", () => {
                day.punches.push({ kind: day.punches.length % 2 === 0 ? "IN" : "OUT", time_raw: "", time_hhmm: "" });
                setDirty(); renderEditor();
            }));
            const remove = miniButton("− última", () => {
                day.punches.pop(); setDirty(); renderEditor();
            });
            remove.disabled = day.punches.length === 0;
            actionsRow.append(remove);
            actions.append(actionsRow);
        }));
        wrap.append(table);
        editor.append(wrap);
    }

    function renderPayslip() {
        const warnings = payslipWarnings(state.value);
        const labels = [];
        const seen = new Set();
        state.value.pages.forEach(page => page.fields.forEach(field => {
            if (!seen.has(field.label)) { seen.add(field.label); labels.push(field.label); }
        }));
        const wrap = make("div", "table-wrap");
        const table = make("table", "data-table");
        const head = table.createTHead().insertRow();
        ["Pág.", "Mês", "Ano", ...labels].forEach(label => makeHeader(head, label));
        const body = table.createTBody();
        state.value.pages.forEach((page, pageIndex) => {
            const warning = warnings[pageIndex];
            const row = body.insertRow();
            decorateWarning(row, warning);
            const pageCell = row.insertCell();
            pageCell.append(document.createTextNode(String(page.page)));
            warningList(pageCell, warning);
            const monthCell = row.insertCell();
            monthCell.append(input(page.month, value => { page.month = value; setDirty(); }, "Mês"));
            const yearCell = row.insertCell();
            yearCell.append(input(page.year, value => { page.year = value; setDirty(); }, "Ano"));
            labels.forEach(label => {
                const cell = row.insertCell();
                let field = page.fields.find(candidate => candidate.label === label);
                cell.append(input(field ? field.value : "", value => {
                    if (!field) {
                        field = { code: "", label, reference: "", value: "" };
                        page.fields.push(field);
                    }
                    field.value = value;
                    setDirty();
                }, label));
            });
        });
        wrap.append(table);
        editor.append(wrap);
        renderPayslipDetails();
    }

    function renderPayslipDetails() {
        const section = make("section", "details-section");
        section.append(make("h2", "", "Dados auditáveis"));
        section.append(make("p", "details-intro", "Edite código, descrição, referência e bases sem misturar bases/totais às verbas da planilha."));
        state.value.pages.forEach(page => {
            const details = make("details", "page-details");
            details.append(make("summary", "", `Página ${page.page} — ${page.month || "??"}/${page.year || "????"}`));
            const body = make("div", "detail-body");
            body.append(detailTitle("Verbas", "+ verba", () => {
                page.fields.push({ code: "", label: "Nova verba", reference: "", value: "" });
                setDirty(); renderEditor();
            }));
            if (page.fields.length) body.append(fieldsDetailTable(page));
            else body.append(make("div", "empty-note", "Nenhuma verba extraída nesta página."));

            body.append(detailTitle("Bases e totais", "+ base/total", () => {
                page.bases.push({ label: "Nova base", value: "" });
                setDirty(); renderEditor();
            }));
            if (page.bases.length) body.append(basesDetailTable(page));
            else body.append(make("div", "empty-note", "Nenhuma base ou total extraído nesta página."));
            details.append(body);
            section.append(details);
        });
        editor.append(section);
    }

    function fieldsDetailTable(page) {
        const table = make("table", "compact-table");
        const head = table.createTHead().insertRow();
        ["Código", "Descrição", "Referência", "Valor", ""].forEach(label => makeHeader(head, label));
        const body = table.createTBody();
        page.fields.forEach((field, index) => {
            const row = body.insertRow();
            row.insertCell().append(input(field.code, value => { field.code = value; setDirty(); }, "Código"));
            const labelInput = input(field.label, value => { field.label = value; setDirty(); }, "Descrição");
            labelInput.addEventListener("change", renderEditor, { once: true });
            row.insertCell().append(labelInput);
            row.insertCell().append(input(field.reference, value => { field.reference = value; setDirty(); }, "Referência"));
            row.insertCell().append(input(field.value, value => { field.value = value; setDirty(); }, "Valor"));
            row.insertCell().append(miniButton("Excluir", () => { page.fields.splice(index, 1); setDirty(); renderEditor(); }));
        });
        return table;
    }

    function basesDetailTable(page) {
        const table = make("table", "compact-table");
        const head = table.createTHead().insertRow();
        ["Base/total", "Valor", ""].forEach(label => makeHeader(head, label));
        const body = table.createTBody();
        page.bases.forEach((base, index) => {
            const row = body.insertRow();
            row.insertCell().append(input(base.label, value => { base.label = value; setDirty(); }, "Base ou total"));
            row.insertCell().append(input(base.value, value => { base.value = value; setDirty(); }, "Valor"));
            row.insertCell().append(miniButton("Excluir", () => { page.bases.splice(index, 1); setDirty(); renderEditor(); }));
        });
        return table;
    }

    function detailTitle(title, buttonText, action) {
        const container = make("div", "detail-title");
        container.append(make("strong", "", title), miniButton(buttonText, action));
        return container;
    }

    function timecardWarnings(value) {
        const output = [];
        let previous = null;
        value.pages.forEach(page => page.days.forEach(day => {
            const reasons = [];
            let level = "none";
            const current = parseDate(day.date_raw);
            if (current !== null) {
                if (previous !== null && current !== previous + 86400000) {
                    reasons.push("data não sequencial"); level = "red";
                }
                previous = current;
            }
            if (day.punches.length % 2 !== 0) {
                reasons.push("batidas ímpares"); if (level === "none") level = "yellow";
            }
            if (JSON.stringify(day).includes("?")) {
                reasons.push("leitura contém ?"); if (level === "none") level = "yellow";
            }
            output.push({ level, reasons });
        }));
        return output;
    }

    function payslipWarnings(value) {
        const output = [];
        let previous = null;
        value.pages.forEach(page => {
            const reasons = [];
            let level = "none";
            const current = parseCompetence(page.year, page.month);
            if (current !== null) {
                if (previous !== null && current !== previous + 1) {
                    reasons.push("mês não sequencial"); level = "red";
                }
                previous = current;
            }
            if (page.fields.length === 0 && page.bases.length === 0) {
                reasons.push("página vazia"); if (level === "none") level = "yellow";
            }
            if (JSON.stringify(page).includes("?")) {
                reasons.push("leitura contém ?"); if (level === "none") level = "yellow";
            }
            output.push({ level, reasons });
        });
        return output;
    }

    function parseDate(raw) {
        const match = /^(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{4})$/.exec(raw || "");
        if (!match) return null;
        const day = Number(match[1]);
        const month = Number(match[2]);
        const year = Number(match[3]);
        const date = Date.UTC(year, month - 1, day);
        const parsed = new Date(date);
        return parsed.getUTCFullYear() === year && parsed.getUTCMonth() === month - 1 && parsed.getUTCDate() === day
            ? date : null;
    }

    function parseCompetence(year, month) {
        if (!/^\d{4}$/.test(year || "") || !/^(0[1-9]|1[0-2])$/.test(month || "")) return null;
        return Number(year) * 12 + Number(month) - 1;
    }

    function decorateWarning(row, warning) {
        if (warning.level !== "none") row.classList.add(`warning-${warning.level}`);
        if (warning.reasons.length) row.title = warning.reasons.join("; ");
    }

    function warningList(cell, warning) {
        if (!warning.reasons.length) return;
        const list = make("ul", "warning-reasons");
        warning.reasons.forEach(reason => list.append(make("li", "", reason)));
        cell.append(list);
    }

    function input(value, onInput, label, className = "data-input") {
        const element = document.createElement("input");
        element.type = "text";
        element.className = className;
        element.value = value == null ? "" : value;
        element.setAttribute("aria-label", label);
        element.title = label;
        element.autocomplete = "off";
        element.addEventListener("input", event => onInput(event.target.value));
        return element;
    }

    function caption(text) { return make("span", "cell-caption", text); }
    function make(tag, className = "", text = null) {
        const element = document.createElement(tag);
        if (className) element.className = className;
        if (text !== null) element.textContent = text;
        return element;
    }
    function makeHeader(row, text) { const cell = document.createElement("th"); cell.textContent = text; row.append(cell); }
    function miniButton(text, action) {
        const button = make("button", "mini-button", text);
        button.type = "button";
        button.addEventListener("click", action);
        return button;
    }

    saveButton.addEventListener("click", async () => {
        clearError(reviewError);
        try { await save(); }
        catch (error) { showError(reviewError, error.message || "Não foi possível salvar as correções."); }
    });

    async function save() {
        if (!state.dirty) return;
        saveButton.disabled = true;
        downloadButton.disabled = true;
        saveState.textContent = "Salvando correções…";
        const response = await fetch(`/api/transcricoes/${encodeURIComponent(state.jobId)}`, {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ value: state.value })
        });
        if (!response.ok) {
            const body = await readResponse(response);
            setDirty(true);
            throw new Error(body.erro || "O servidor recusou as correções.");
        }
        setDirty(false);
        downloadButton.disabled = false;
    }

    downloadButton.addEventListener("click", async () => {
        clearError(reviewError);
        downloadButton.disabled = true;
        try {
            await save();
            const format = $("#download-format").value;
            const response = await fetch(`/api/transcricoes/${encodeURIComponent(state.jobId)}/planilha?formato=${encodeURIComponent(format)}`);
            if (!response.ok) {
                const body = await readResponse(response);
                throw new Error(body.erro || "Não foi possível gerar o arquivo.");
            }
            const blob = await response.blob();
            const url = URL.createObjectURL(blob);
            const anchor = document.createElement("a");
            anchor.href = url;
            anchor.download = `${state.type}.${format}`;
            document.body.append(anchor);
            anchor.click();
            anchor.remove();
            setTimeout(() => URL.revokeObjectURL(url), 1000);
        } catch (error) {
            showError(reviewError, error.message || "Não foi possível baixar o arquivo.");
        } finally {
            downloadButton.disabled = false;
        }
    });

    $("#new-document").addEventListener("click", () => {
        state.pollingToken++;
        if (state.pdfUrl) URL.revokeObjectURL(state.pdfUrl);
        window.location.reload();
    });

    async function readResponse(response) {
        const text = await response.text();
        if (!text) return {};
        try { return JSON.parse(text); }
        catch { return { erro: text }; }
    }

    function wait(milliseconds) { return new Promise(resolve => setTimeout(resolve, milliseconds)); }
})();
