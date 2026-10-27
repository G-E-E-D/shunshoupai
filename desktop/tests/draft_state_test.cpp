#include "draft_state.h"
#include <QCoreApplication>
#include <QJsonArray>
#include <QProcess>
#include <QTemporaryDir>
#include <iostream>
#include <stdexcept>

namespace {
const QString origin = "http://127.0.0.1:43127";
const QString user = "11111111-1111-4111-8111-111111111111";
const QJsonObject pending{{"request_id", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"},
    {"events", QJsonArray{QJsonObject{{"title", "original submitted content"}}}}};
const QJsonObject review{{"events", QJsonArray{QJsonObject{{"title", "edited unsubmitted content"}}}}};
const QJsonObject editor{{"event", QJsonObject{{"id", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}, {"version", 3}}},
    {"title", "manual unsaved change"}, {"start_local", "2030-10-27T09:00:00"}, {"end_local", "2030-10-27T10:00:00"}};
void require(bool condition, const char *message) { if (!condition) throw std::runtime_error(message); }
}

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    try {
        if (app.arguments().size() == 3 && app.arguments().at(1) == "--read-drafts") {
            QSettings restarted(app.arguments().at(2), QSettings::IniFormat);
            require(DraftState::read(restarted, DraftState::key("pending", origin, user)) == pending, "pending content changed across restart");
            require(DraftState::read(restarted, DraftState::key("review", origin, user)) == review, "review draft did not survive restart");
            require(DraftState::read(restarted, DraftState::key("editor", origin, user) + "/test") == editor, "manual fields/version did not survive restart");
            require(DraftState::read(restarted, DraftState::key("review", "http://127.0.0.1:43128", user)).isEmpty(), "another backend inherited review");
            require(DraftState::read(restarted, DraftState::key("review", origin, "22222222-2222-4222-8222-222222222222")).isEmpty(), "another account inherited review");
            return 0;
        }
        QTemporaryDir directory;
        require(directory.isValid(), "temporary directory unavailable");
        const QString path = directory.filePath("drafts.ini");
        {
            QSettings settings(path, QSettings::IniFormat);
            DraftState::write(settings, DraftState::key("pending", origin, user), pending);
            DraftState::write(settings, DraftState::key("review", origin, user), review);
            DraftState::write(settings, DraftState::key("editor", origin, user) + "/test", editor);
            require(settings.status() == QSettings::NoError, "drafts could not be written");
        }
        QProcess child;
        child.start(app.applicationFilePath(), {"--read-drafts", path});
        require(child.waitForFinished(10000) && child.exitStatus() == QProcess::NormalExit && child.exitCode() == 0, "new process failed to restore scoped draft states");
        std::cout << "PASS: new process restores separate pending/review/editor states and original version; account/backend isolation\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
